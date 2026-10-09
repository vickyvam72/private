package com.lumisignal.idxscreener

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.lumisignal.idxscreener.model.DataResult
import com.lumisignal.idxscreener.network.CapturedStockbitSession
import com.lumisignal.idxscreener.network.StockbitTokenPolicy
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class StockbitLoginActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private val captured = AtomicBoolean(false)
    private val allowedOrigins = setOf("https://stockbit.com", "https://*.stockbit.com")

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(false)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(7,17,31)) }
        val title = TextView(this).apply { text="Login Stockbit • Read-only";textSize=18f;setTextColor(Color.WHITE);setPadding(24) }
        status = TextView(this).apply { text="Login pada halaman Stockbit. Lumi hanya membaca token sesi dari respons autentikasi; password dan PIN tidak direkam.";textSize=12f;setTextColor(Color.rgb(145,164,184));setPadding(24) }
        progress = ProgressBar(this).apply { isIndeterminate=true }
        webView = WebView(this)
        val cancel=Button(this).apply{text="TUTUP";setOnClickListener{finish()}}
        root.addView(title,LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(status,LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(progress,LinearLayout.LayoutParams.MATCH_PARENT,6)
        root.addView(webView,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f))
        root.addView(cancel,LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT)
        setContentView(root)
        webView.settings.apply { javaScriptEnabled=true;domStorageEnabled=true;databaseEnabled=false;allowFileAccess=false;allowContentAccess=false;mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW;setSupportMultipleWindows(false);javaScriptCanOpenWindowsAutomatically=false;cacheMode=WebSettings.LOAD_DEFAULT }
        CookieManager.getInstance().apply { setAcceptCookie(true);setAcceptThirdPartyCookies(webView,false) }
        installSessionBridge()
        webView.webViewClient=object:WebViewClient(){
            override fun onPageStarted(view:WebView,url:String?,favicon:android.graphics.Bitmap?){progress.visibility=View.VISIBLE;if(!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))view.evaluateJavascript(CAPTURE_SCRIPT,null)}
            override fun onPageFinished(view:WebView,url:String?){progress.visibility=View.GONE;if(!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))view.evaluateJavascript(CAPTURE_SCRIPT,null)}
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean{val ok=isStockbitHost(request.url.host);if(!ok)status.text="Navigasi di luar domain Stockbit diblokir. Gunakan login email/username di halaman ini.";return !ok}
        }
        webView.loadUrl("https://stockbit.com/login")
    }

    @SuppressLint("JavascriptInterface") private fun installSessionBridge(){
        if(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))WebViewCompat.addWebMessageListener(webView,BRIDGE_NAME,allowedOrigins,object:WebViewCompat.WebMessageListener{
            override fun onPostMessage(view:WebView,message:WebMessageCompat,sourceOrigin:Uri,isMainFrame:Boolean,replyProxy:JavaScriptReplyProxy){receiveSession(message.data,sourceOrigin.host)}
        }) else webView.addJavascriptInterface(LegacyBridge(),BRIDGE_NAME)
        if(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))WebViewCompat.addDocumentStartJavaScript(webView,CAPTURE_SCRIPT,allowedOrigins)
    }
    private inner class LegacyBridge{@JavascriptInterface fun postMessage(payload:String)=runOnUiThread{receiveSession(payload,Uri.parse(webView.url?:"").host)}}
    private fun receiveSession(payload:String?,sourceHost:String?){
        if(captured.get()||payload.isNullOrBlank()||!isStockbitHost(sourceHost))return
        val json=runCatching{JSONObject(payload)}.getOrNull()?:return
        val captureSource=json.optString("source","response")
        if(captureSource!="storage"&&!StockbitTokenPolicy.tokenUrlAllowed(json.optString("url")))return
        val refresh=json.optString("refresh").takeIf(StockbitTokenPolicy::looksLikeJwt)?:return
        val access=json.optString("access").takeIf(StockbitTokenPolicy::looksLikeJwt);val expiry=json.optLong("accessExp",0).takeIf{it>0}
        if(!captured.compareAndSet(false,true))return
        status.text="Sesi ditemukan. Memvalidasi akses market data…";progress.visibility=View.VISIBLE
        lifecycleScope.launch{when(val result=(application as LumiApplication).repository.acceptStockbitWebSession(CapturedStockbitSession(refresh,access,expiry))){
            is DataResult.Success->{status.text="Stockbit Connected ✓ Token disimpan terenkripsi.";progress.visibility=View.GONE;setResult(Activity.RESULT_OK);webView.postDelayed({finish()},900)}
            is DataResult.Error->{captured.set(false);status.text=result.userMessage;progress.visibility=View.GONE}
        }}
    }
    private fun isStockbitHost(host:String?):Boolean {
        val normalized=host?.lowercase()?.trimEnd('.')?:return false
        return normalized=="stockbit.com"||normalized.endsWith(".stockbit.com")
    }
    override fun onDestroy(){webView.stopLoading();webView.loadUrl("about:blank");webView.removeAllViews();webView.destroy();super.onDestroy()}
    companion object{
        private const val BRIDGE_NAME="LumiStockbitSession"
        private val CAPTURE_SCRIPT="""
          (function(){if(window.__lumiStockbitCaptureInstalled)return;window.__lumiStockbitCaptureInstalled=true;
          function j(v){return typeof v==='string'&&/^eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+${'$'}/.test(v)}
          function ok(r){try{var u=new URL(r,location.href),h=u.hostname.toLowerCase(),p=u.pathname;if(h==='wssocial.stockbit.com')return true;return h==='exodus.stockbit.com'&&/^\/(?:login|auth)\//i.test(p)}catch(_){return false}}
          function f(n,k,s){if(!n||typeof n!=='object')return null;s=s||new WeakSet();if(s.has(n))return null;s.add(n);var re=k==='refresh'?/^refresh(?:_token)?${'$'}/i:/^access(?:_token)?${'$'}/i;for(var x in n){var v=n[x];if(re.test(x)){if(j(v))return v;if(v&&typeof v==='object'&&j(v.token))return v.token}var q=f(v,k,s);if(q)return q}return null}
          function exp(t){try{var p=t.split('.')[1].replace(/-/g,'+').replace(/_/g,'/');while(p.length%4)p+='=';return JSON.parse(atob(p)).exp||0}catch(_){return 0}}
          function emit(r,a,u,s){if(!r||!j(r))return;window.LumiStockbitSession.postMessage(JSON.stringify({url:String(u||location.href),source:s||'response',refresh:r,access:j(a)?a:'',accessExp:j(a)?exp(a):0}))}
          function inspect(u,t){if(!ok(u)||!t)return;try{var b=JSON.parse(t);emit(f(b,'refresh'),f(b,'access'),u,'response')}catch(_){}}
          function scanStorage(){try{var r=null,a=null;for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i)||'',v=localStorage.getItem(k);if(/refresh(?:_?token)?/i.test(k)&&j(v))r=v;if(/access(?:_?token)?/i.test(k)&&j(v))a=v}emit(r,a,location.href,'storage')}catch(_){}}
          var of=window.fetch;if(of)window.fetch=function(){var a=arguments;return of.apply(this,a).then(function(r){try{var c=r.clone(),u=r.url||String(a[0]);c.text().then(function(t){inspect(u,t)})}catch(_){}return r})};
          var oo=XMLHttpRequest.prototype.open,os=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.open=function(m,u){this.__lumiUrl=String(u);return oo.apply(this,arguments)};XMLHttpRequest.prototype.send=function(){this.addEventListener('load',function(){try{if(!this.responseType||this.responseType==='text')inspect(this.responseURL||this.__lumiUrl,this.responseText);scanStorage()}catch(_){}});return os.apply(this,arguments)};
          var si=Storage.prototype.setItem;Storage.prototype.setItem=function(k,v){var z=si.apply(this,arguments);scanStorage();return z};scanStorage();})();
        """.trimIndent()
    }
}
