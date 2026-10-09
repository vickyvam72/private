package com.lumisignal.idxscreener

import android.content.Intent
import android.Manifest
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lumisignal.idxscreener.data.*
import com.lumisignal.idxscreener.engine.IDXTickSizeEngine
import com.lumisignal.idxscreener.model.*
import com.lumisignal.idxscreener.ui.MainViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val Navy = Color(0xFF07111F)
private val Surface = Color(0xFF101D2D)
private val Surface2 = Color(0xFF162638)
private val Lumi = Color(0xFF24B7ED)
private val Blue = Color(0xFF2E86E9)
private val Good = Color(0xFF35D49A)
private val Warn = Color(0xFFFFC857)
private val Bad = Color(0xFFFF6B72)
private val Muted = Color(0xFF91A4B8)

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LumiTheme { LumiApp(vm) } }
    }
}

@Composable private fun LumiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary=Lumi,secondary=Blue,background=Navy,surface=Surface,onBackground=Color.White,onSurface=Color.White,error=Bad), content=content)
}

private data class NavItem(val route:String,val label:String,val icon: androidx.compose.ui.graphics.vector.ImageVector)
private val navItems=listOf(NavItem("home","Home",Icons.Default.Home),NavItem("screener","Screener",Icons.Default.Search),NavItem("portfolio","Portfolio",Icons.Default.AccountCircle),NavItem("history","History",Icons.Default.List),NavItem("settings","Settings",Icons.Default.Settings))
private data class RankedCandidate(val row:ScreeningResultEntity,val score:Double)
private data class HistoryTickerGroup(val ticker:String,val companyName:String,val referenceDate:Long,val signals:List<SignalEntity>)

@Composable private fun LumiApp(vm: MainViewModel) {
    val nav= rememberNavController(); val message by vm.message.collectAsStateWithLifecycle(); val resend by vm.pendingResend.collectAsStateWithLifecycle()
    Scaffold(containerColor=Navy,bottomBar={BottomBar(nav)}) { pad ->
        NavHost(navController=nav,startDestination="home",modifier=Modifier.padding(pad)) {
            composable("home") { HomeScreen(vm,{nav.navigate("history")},{nav.navigate("candidate/$it")}) }
            composable("screener") { ScreenerScreen(vm) }
            composable("portfolio") { PortfolioScreen(vm) }
            composable("history") { HistoryScreen(vm){nav.navigate("detail/$it")} }
            composable("settings") { SettingsScreen(vm) }
            composable("detail/{uuid}") { back -> SignalDetailScreen(vm,back.arguments?.getString("uuid").orEmpty()){nav.popBackStack()} }
            composable("candidate/{id}") { back -> ScreeningCandidateDetailScreen(vm,back.arguments?.getString("id").orEmpty()){nav.popBackStack()} }
        }
    }
    message?.let { SnackbarDialog(it, vm::clearMessage) }
    resend?.let { id -> AlertDialog(onDismissRequest=vm::cancelResend,title={Text("Signal sudah pernah dikirim")},text={Text("Snapshot lama tidak akan diubah. Kirim ulang pesan yang sama?")},confirmButton={TextButton(onClick={vm.send(id,true)}){Text("Kirim ulang")}},dismissButton={TextButton(onClick=vm::cancelResend){Text("Batal")}}) }
}

@Composable private fun BottomBar(nav:NavController) {
    val route=nav.currentBackStackEntryAsState().value?.destination?.route
    NavigationBar(containerColor=Surface) { navItems.forEach { item -> NavigationBarItem(selected=route==item.route,onClick={nav.navigate(item.route){popUpTo("home"){saveState=true};launchSingleTop=true;restoreState=true}},icon={Icon(item.icon,null)},label={Text(item.label)}) } }
}

@Composable private fun Header() {
    Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {
        Image(painterResource(R.drawable.lumi_logo),"Logo Lumi Signal",Modifier.size(58.dp),contentScale=ContentScale.Fit)
        Spacer(Modifier.width(12.dp)); Column { Text("Lumi Signal",fontSize=24.sp,fontWeight=FontWeight.Bold);Text("IDX Screener",color=Lumi,fontSize=14.sp,letterSpacing=1.sp);Text("v${BuildConfig.VERSION_NAME} • build ${BuildConfig.VERSION_CODE}",color=Muted,fontSize=10.sp) }
    }
}

@Composable private fun HomeScreen(vm:MainViewModel,onHistory:()->Unit,onCandidateDetail:(String)->Unit) {
    val con by vm.connections.collectAsStateWithLifecycle(); val latest by vm.latest.collectAsStateWithLifecycle(); val latestRun by vm.latestRun.collectAsStateWithLifecycle(); val perf by vm.performance.collectAsStateWithLifecycle(); val work by vm.work.collectAsStateWithLifecycle(); val logs by vm.activity.collectAsStateWithLifecycle()
    val signals by vm.signals.collectAsStateWithLifecycle()
    val auditEnabled by vm.backgroundAudit.collectAsStateWithLifecycle()
    val portfolio by vm.portfolio.collectAsStateWithLifecycle()
    var strategy by rememberSaveable{mutableStateOf(StrategyType.QUIET_ACCUMULATION)}
    var showAuditWarning by remember{mutableStateOf(false)}
    val ranked=remember(latest,strategy){
        latest.mapNotNull { row ->
            runCatching { CandidateJson.decode(org.json.JSONObject(row.candidateJson)) }.getOrNull()
                ?.takeIf { it.strategy == strategy && it.passed }
                ?.let { candidate -> RankedCandidate(row,candidate.strategyScore) }
        }.sortedWith(compareByDescending<RankedCandidate>{it.score}.thenBy{it.row.ticker}).take(5)
    }
    val screeningReference=remember(latest){latest.groupingBy{it.referenceDate}.eachCount().maxByOrNull{it.value}?.key}
    val context=LocalContext.current
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
    val stockbitLogin=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){r->if(r.resultCode==android.app.Activity.RESULT_OK)vm.refreshStockbitConnection()}
    LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { Header() }
        item { CardBlock { Text("DATA CONNECTION",style=MaterialTheme.typography.labelLarge,color=Muted);Spacer(Modifier.height(10.dp));StatusLine("Stockbit Market Data",con.marketData);StatusLine("Broker Summary",con.brokerSummary);StatusLine("Running Trade",con.runningTrade);StatusLine("Stockbit Account",con.stockbit);StatusLine("Stockbit Plan",stockbitTierLabel(con.stockbitTier));StatusLine("Portfolio Sekuritas",con.brokerage);StatusLine("Telegram",con.telegram);if(con.stockbit!="Connected"){Spacer(Modifier.height(8.dp));OutlinedButton(onClick={stockbitLogin.launch(Intent(context,StockbitLoginActivity::class.java))},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Info,null);Spacer(Modifier.width(8.dp));Text("LOGIN STOCKBIT VIA WEBVIEW")}} } }
        if(con.stockbit=="Connected"&&!con.stockbitTier.strategiesUnlocked) item { WarningCard(con.stockbitTierMessage) }
        item { Button(onClick={vm.startScreening()},enabled=!work.running&&con.stockbit=="Connected"&&con.stockbitTier.strategiesUnlocked,modifier=Modifier.fillMaxWidth().height(54.dp)){Icon(if(con.stockbitTier.strategiesUnlocked)Icons.Default.Star else Icons.Default.Lock,null);Spacer(Modifier.width(6.dp));Text(if(con.stockbitTier.strategiesUnlocked)"SCREENING 10 STRATEGI" else "STOCKBIT PRO DIPERLUKAN")} }
        if(work.running) item { ProgressCard(work.stage,work.current,work.total,vm::cancelScreening) }
        item { CardBlock { DetailRow("Data screening sampai",(latestRun?.referenceDate?:screeningReference)?.let{date(it*1000)}?:"Belum ada screening");DetailRow("Harga audit","Live ${date(System.currentTimeMillis())} • ${if(auditEnabled)"AKTIF" else "OFF"}") } }
        latestRun?.let { run -> item { ScreeningCoverageCard(run) } }
        item { SectionTitle("PILIH STRATEGI","Top 5 dihitung ulang secara independen untuk setiap strategi") }
        item { StrategyDropdown(strategy){strategy=it} }
        item { CardBlock { Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("AUDIT LATAR BELAKANG",fontWeight=FontWeight.Bold);Text("Setiap 1 menit • hanya sinyal Top 5 dari 10 strategi",color=Muted,fontSize=11.sp)};Switch(checked=auditEnabled,onCheckedChange={on->if(on)showAuditWarning=true else vm.setBackgroundAudit(false)})};Text("Bukan seluruh saham IDX. ENTRY, TP, dan SL hanya memunculkan notifikasi HP; Telegram hanya menerima sinyal yang Anda kirim manual.",color=Warn,fontSize=10.sp,modifier=Modifier.padding(top=8.dp))} }
        item { SectionTitle("TOP 5 • STRATEGI ${strategy.number}","${strategy.label} • ${strategy.description}") }
        if(latest.isEmpty()) item { EmptyCard("Tekan SCREENING 10 STRATEGI untuk mengambil kandidat aktual. Stockbit wajib; Telegram opsional.") }
        else if(ranked.isEmpty()) item { EmptyCard("Tidak ada saham yang benar-benar lolos ambang ${strategy.minimumMatches}/10 pada strategi ini. Hasil tidak dipaksakan menjadi lima.") }
        else itemsIndexed(ranked,key={_,v->v.row.id}) { index,item ->
            val candidate=runCatching{CandidateJson.decode(org.json.JSONObject(item.row.candidateJson))}.getOrNull()
            CandidateCard(item.row,candidate?.let { c -> signals.firstOrNull { it.uuid==c.signalUuid() } },onSend={vm.send(item.row.id)},onDetail={onCandidateDetail(item.row.id)},rank=index+1,displayScore=item.score,isOwned=item.row.ticker.removeSuffix(".JK") in (portfolio.snapshot?.ownedTickers?:emptySet()))
        }
        item { SectionTitle("PERFORMANCE","TP / (TP + SL), tanpa holding") }
        item { PerformanceGrid(perf) }
        item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){TextButton(onClick=onHistory){Text("Lihat Semua");Icon(Icons.Default.KeyboardArrowRight,null)}} }
        item { SectionTitle("ACTIVITY LOG","Token tidak pernah dicatat") }
        items(logs.take(6),key={it.id}) { log -> Row(Modifier.fillMaxWidth().padding(vertical=4.dp)){Text(time(log.timestamp),color=Muted,modifier=Modifier.width(54.dp));Column{Text(log.event,fontWeight=FontWeight.Medium);log.detail?.let{Text(it,color=Muted,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)}}} }
    }
    if(showAuditWarning) AlertDialog(onDismissRequest={showAuditWarning=false},title={Text("Izinkan audit semua strategi?")},text={Text("Audit memeriksa seluruh sinyal Top 5 dari 10 strategi setiap satu menit selama sesi BEI. Jangan batasi Lumi Signal melalui penghemat baterai. Perubahan ENTRY, TP, dan SL hanya menjadi notifikasi HP dan tidak dikirim ke Telegram.")},confirmButton={TextButton(onClick={showAuditWarning=false;if(Build.VERSION.SDK_INT>=33)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);vm.setBackgroundAudit(true);runCatching{context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))}}){Text("AKTIFKAN")}},dismissButton={TextButton(onClick={showAuditWarning=false}){Text("BATAL")}})
}

@Composable private fun ScreenerScreen(vm:MainViewModel) {
    var query by remember{mutableStateOf("")};var selectedStrategy by rememberSaveable{mutableStateOf(StrategyType.QUIET_ACCUMULATION)};val search by vm.search.collectAsStateWithLifecycle();val connections by vm.connections.collectAsStateWithLifecycle()
    val selected=search.analyses.firstOrNull { it.strategy==selectedStrategy };val market by vm.marketContext.collectAsStateWithLifecycle();val portfolio by vm.portfolio.collectAsStateWithLifecycle()
    LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Header();Spacer(Modifier.height(8.dp));SectionTitle("ANALISIS SAHAM IDX","Satu ticker langsung dinilai oleh seluruh 10 strategi") }
        if(!connections.stockbitTier.strategiesUnlocked) item { WarningCard(connections.stockbitTierMessage) }
        item { Row(verticalAlignment=Alignment.CenterVertically){OutlinedTextField(query,{query=it.uppercase().filter(Char::isLetter).take(6)},enabled=connections.stockbitTier.strategiesUnlocked,label={Text("BBCA, BMRI, ANTM...")},singleLine=true,leadingIcon={Icon(Icons.Default.Search,null)},modifier=Modifier.weight(1f));Spacer(Modifier.width(8.dp));Button(onClick={vm.searchTicker(query)},enabled=query.isNotBlank()&&!search.loading&&connections.stockbitTier.strategiesUnlocked,modifier=Modifier.height(56.dp)){Text(if(connections.stockbitTier.strategiesUnlocked)"ANALISIS" else "PRO")}} }
        if(search.loading) item{LinearProgressIndicator(Modifier.fillMaxWidth())}
        search.error?.let{item{ErrorCard(it)}}
        search.warning?.let{item{WarningCard(it)}}
        search.result?.let{ series -> item { Column { QuoteCard(series);if(series.ticker.removeSuffix(".JK") in (portfolio.snapshot?.ownedTickers?:emptySet())) Text("● SAHAM INI ADA DI PORTFOLIO",color=Good,fontWeight=FontWeight.Bold,fontSize=11.sp,modifier=Modifier.padding(top=8.dp)) } } }
        market?.takeIf { it.ticker==search.result?.ticker }?.let { context -> item { MarketContextCard(context) } }
        if(search.analyses.isNotEmpty()) item {
            StrategyScoreOverview(search.analyses,selectedStrategy){selectedStrategy=it}
        }
        selected?.let{ analysis -> item { SearchAnalysisCard(analysis) } }
        if(search.result!=null&&selected!=null) item {
            Text("CHART • STRATEGI ${selectedStrategy.number}",color=Muted,fontWeight=FontWeight.Bold)
            CardBlock{CandidateCandlestickChart(search.result!!.candles.takeLast(90),selected)}
        }
        if(!search.loading&&search.result==null&&search.error==null) item{EmptyCard("Hasil di halaman ini hanya untuk saham yang Anda masukkan. Top 5 pasar tersedia di Home setelah SCREENING IDX selesai.")}
    }
}

@Composable private fun CandidateCard(row:ScreeningResultEntity,signal:SignalEntity?,onSend:()->Unit,onDetail:()->Unit,rank:Int=row.rank,displayScore:Double=row.score,isOwned:Boolean=false) {
    val c=remember(row.candidateJson){runCatching{CandidateJson.decode(org.json.JSONObject(row.candidateJson))}.getOrNull()}
    Card(colors=CardDefaults.cardColors(containerColor=Surface),shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically){Text("#$rank",color=Lumi,fontWeight=FontWeight.Bold);Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(row.ticker.removeSuffix(".JK"),fontSize=22.sp,fontWeight=FontWeight.Black);Text(row.companyName,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)};ScoreCircle(displayScore)}
        c?.let { Text("STRATEGI ${it.strategy.number} • ${it.strategy.label}",color=Good,fontWeight=FontWeight.Bold) }
        if(isOwned)Text("● DIMILIKI DI PORTFOLIO",color=Lumi,fontSize=10.sp,fontWeight=FontWeight.Bold)
        c?.let { candidate ->
            Text("${candidate.matchedCriteria}/${candidate.totalCriteria} filter cocok",color=Muted,fontSize=11.sp)
            val actualEntry=signal?.entryTriggeredPrice
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Metric("Entry",actualEntry?.let{"Rp${it.toInt().formatIdr()} ✓"}?:"Rp${candidate.tradePlan.entryLow.formatIdr()}–${candidate.tradePlan.entryHigh.formatIdr()}",if(actualEntry!=null)Good else Color.White);Metric("TP","Rp${candidate.tradePlan.takeProfit.formatIdr()}${if(signal?.status==SignalStatus.TAKE_PROFIT)" ✓" else ""}",if(signal?.status==SignalStatus.TAKE_PROFIT)Good else Color.White);Metric("SL","Rp${candidate.tradePlan.stopLoss.formatIdr()}${if(signal?.status==SignalStatus.STOP_LOSS)" ✓" else ""}",if(signal?.status==SignalStatus.STOP_LOSS)Bad else Color.White)}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){MiniScore("Strategi",candidate.strategyScore);MiniScore("Broker",candidate.scores.broker);MiniScore("Volume",candidate.scores.volume);MiniScore("Data",100.0-candidate.dataLimitations.size*10)}
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick=onDetail,modifier=Modifier.weight(1f)){Icon(Icons.Default.Info,null);Spacer(Modifier.width(6.dp));Text("DETAIL")}
            Button(onClick=onSend,modifier=Modifier.weight(1f)){Icon(Icons.Default.Send,null);Spacer(Modifier.width(6.dp));Text("TELEGRAM")}
        }
    } }
}

@Composable private fun ScreeningCandidateDetailScreen(vm:MainViewModel,id:String,onBack:()->Unit) {
    val latest by vm.latest.collectAsStateWithLifecycle()
    val signals by vm.signals.collectAsStateWithLifecycle()
    val chart by vm.chart.collectAsStateWithLifecycle()
    val market by vm.marketContext.collectAsStateWithLifecycle();val portfolio by vm.portfolio.collectAsStateWithLifecycle()
    val row=latest.firstOrNull{it.id==id}
    val candidate=remember(row?.candidateJson){row?.let{runCatching{CandidateJson.decode(org.json.JSONObject(it.candidateJson))}.getOrNull()}}
    LaunchedEffect(candidate?.ticker){candidate?.let{vm.loadChart(it.ticker)}}
    LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item{Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)};Column{Text("ANALISIS LENGKAP",color=Lumi,fontSize=12.sp,fontWeight=FontWeight.Bold);Text(row?.ticker?.removeSuffix(".JK")?:"Detail Kandidat",fontSize=25.sp,fontWeight=FontWeight.Black)}}}
        if(row==null||candidate==null)item{ErrorCard("Detail screening tidak ditemukan pada batch terbaru.")}
        candidate?.let{c->
            item{CardBlock{Text(c.companyName,fontWeight=FontWeight.Bold);if(c.ticker.removeSuffix(".JK") in (portfolio.snapshot?.ownedTickers?:emptySet()))Text("● DIMILIKI DI PORTFOLIO",color=Good,fontSize=11.sp,fontWeight=FontWeight.Bold);DetailRow("Reference closing",date(c.referenceDate*1000));DetailRow("Reference price","Rp${c.referenceClose.toInt().formatIdr()}");DetailRow("Data source",row?.sourceMode?.replace('_',' ')?:"STOCKBIT")}}
            market?.takeIf{it.ticker==c.ticker}?.let{item{MarketContextCard(it)}}
            item{SearchAnalysisCard(c,signals.firstOrNull { it.uuid==c.signalUuid() })}
            item{
                Text("CANDLE CHART & TECHNICAL INDICATORS",color=Muted,fontWeight=FontWeight.Bold)
                CardBlock{
                    if(chart?.ticker!=c.ticker) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else CandidateCandlestickChart(chart!!.candles.filter { it.epochSeconds <= c.referenceDate }.takeLast(90),c)
                }
            }
            item{Button(onClick={row?.let{vm.send(it.id)}},modifier=Modifier.fillMaxWidth().height(52.dp)){Icon(Icons.Default.Send,null);Spacer(Modifier.width(8.dp));Text("KIRIM KE TELEGRAM")}}
        }
    }
}

@Composable private fun PortfolioScreen(vm:MainViewModel) {
    val state by vm.portfolio.collectAsStateWithLifecycle();val connections by vm.connections.collectAsStateWithLifecycle()
    var pin by remember{mutableStateOf("")};var tab by rememberSaveable{mutableStateOf("POSISI")}
    val context=LocalContext.current
    val stockbitLogin=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){r->if(r.resultCode==android.app.Activity.RESULT_OK)vm.refreshStockbitConnection()}
    LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item{Header();Spacer(Modifier.height(8.dp));SectionTitle("PORTFOLIO","Akun Stockbit Sekuritas • baca-saja")}
        item{CardBlock{
            StatusLine("Login Stockbit",connections.stockbit);StatusLine("Sesi Sekuritas",connections.brokerage)
            Text("Lumi tidak memiliki endpoint beli, jual, amend, cancel, deposit, atau withdrawal.",color=Good,fontSize=11.sp,modifier=Modifier.padding(top=8.dp))
            if(connections.stockbit!="Connected")Button(onClick={stockbitLogin.launch(Intent(context,StockbitLoginActivity::class.java))},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Info,null);Spacer(Modifier.width(8.dp));Text("LOGIN STOCKBIT")}
            else if(connections.brokerage!="Connected"){
                OutlinedTextField(pin,{pin=it.filter(Char::isDigit).take(8)},label={Text("PIN Sekuritas 4–8 digit")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),leadingIcon={Icon(Icons.Default.Lock,null)},modifier=Modifier.fillMaxWidth())
                Text("PIN dipakai satu kali untuk membuka sesi baca-saja dan tidak disimpan.",color=Muted,fontSize=10.sp)
                Button(onClick={vm.unlockPortfolio(pin);pin=""},enabled=pin.length in 4..8&&!state.loading,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Lock,null);Spacer(Modifier.width(8.dp));Text("BUKA PORTFOLIO")}
            }else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={vm.refreshPortfolio()},modifier=Modifier.weight(1f)){Icon(Icons.Default.Refresh,null);Text(" REFRESH")};TextButton(onClick=vm::lockPortfolio,modifier=Modifier.weight(1f)){Text("KUNCI")}}
        }}
        if(state.loading)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
        state.error?.let{item{ErrorCard(it)}}
        state.snapshot?.let{s->
            item{CardBlock{Text("RINGKASAN AKUN",color=Lumi,fontWeight=FontWeight.Bold);Row(Modifier.fillMaxWidth().padding(top=10.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){PortfolioMetric("Total Equity",s.totalEquity,Modifier.weight(1f));PortfolioMetric("Cash",s.cashOnHand,Modifier.weight(1f))};Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){PortfolioMetric("Buying Power",s.buyingPower,Modifier.weight(1f));PortfolioMetric("Withdrawable",s.withdrawableBalance,Modifier.weight(1f))}}}
            item{CardBlock{Text("SETTLEMENT",color=Lumi,fontWeight=FontWeight.Bold);Row(Modifier.fillMaxWidth().padding(top=10.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){PortfolioMetric("T+0",s.settlementT0,Modifier.weight(1f));PortfolioMetric("T+1",s.settlementT1,Modifier.weight(1f));PortfolioMetric("T+2",s.settlementT2,Modifier.weight(1f))};HorizontalDivider(Modifier.padding(vertical=10.dp),color=Surface2);DetailRow("Realized P/L",s.realizedProfitLoss?.let(::money)?:"—",s.realizedProfitLoss?.let{if(it>=0)Good else Bad}?:Muted);DetailRow("Trading Performance",s.tradingPerformancePercent?.let(::signedPct)?:"—",s.tradingPerformancePercent?.let{if(it>=0)Good else Bad}?:Muted)}}
            item{SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){listOf("POSISI","ORDER","RIWAYAT").forEachIndexed{i,label->SegmentedButton(selected=tab==label,onClick={tab=label},shape=SegmentedButtonDefaults.itemShape(i,3)){Text(label,fontSize=10.sp)}}}}
            when(tab){
                "POSISI"->if(s.holdings.isEmpty())item{EmptyCard("Tidak ada posisi yang dapat dipetakan dari respons Stockbit.")}else items(s.holdings,key={it.ticker}){h->CardBlock{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(h.ticker.removeSuffix(".JK"),fontSize=20.sp,fontWeight=FontWeight.Black);Text(h.name?:"Posisi Stockbit",color=Muted,fontSize=11.sp)};Text(h.unrealizedPercent?.let(::signedPct)?:"—",color=h.unrealizedPercent?.let{if(it>=0)Good else Bad}?:Muted,fontWeight=FontWeight.Bold)};DetailRow("Lot / Saham",listOfNotNull(h.lots?.let{"${fmt(it)} lot"},h.shares?.let{"${fmt(it)} saham"}).joinToString(" • ").ifBlank{"—"});DetailRow("Average",h.averagePrice?.let(::money)?:"—");DetailRow("Last",h.lastPrice?.let(::money)?:"—");DetailRow("Market Value",h.marketValue?.let(::money)?:"—");DetailRow("Unrealized P/L",h.unrealizedProfitLoss?.let(::money)?:"—",h.unrealizedProfitLoss?.let{if(it>=0)Good else Bad}?:Muted)}}
                "ORDER"->if(s.openOrders.isEmpty())item{EmptyCard("Tidak ada open order yang dapat dipetakan.")}else items(s.openOrders,key={it.id}){o->CardBlock{Row{Text(o.ticker?:"—",fontWeight=FontWeight.Black,modifier=Modifier.weight(1f));Text(o.status?:"—",color=Warn,fontWeight=FontWeight.Bold)};DetailRow("Side",o.side?:"—");DetailRow("Price",o.price?.let(::money)?:"—");DetailRow("Lot",o.lots?.let(::fmt)?:"—");DetailRow("Filled",o.filledLots?.let(::fmt)?:"—");o.timestamp?.let{DetailRow("Time",it)}}}
                else->if(s.transactions.isEmpty())item{EmptyCard("Riwayat transaksi belum tersedia atau format respons belum dipetakan.")}else items(s.transactions.take(100),key={it.id}){t->CardBlock{Row{Text(t.ticker?:"—",fontWeight=FontWeight.Black,modifier=Modifier.weight(1f));Text(t.side?:"—",color=Lumi,fontWeight=FontWeight.Bold)};DetailRow("Price",t.price?.let(::money)?:"—");DetailRow("Lot",t.lots?.let(::fmt)?:"—");DetailRow("Amount",t.amount?.let(::money)?:"—");DetailRow("Realized P/L",t.realizedProfitLoss?.let(::money)?:"—",t.realizedProfitLoss?.let{if(it>=0)Good else Bad}?:Muted);t.timestamp?.let{DetailRow("Time",it)}}}
            }
            if(s.warnings.isNotEmpty())item{CardBlock{Text("CATATAN DATA",color=Warn,fontWeight=FontWeight.Bold);s.warnings.forEach{Text("• $it",color=Muted,fontSize=10.sp,modifier=Modifier.padding(top=4.dp))}}}
        }
    }
}

@Composable private fun PortfolioMetric(label:String,value:Double?,modifier:Modifier=Modifier)=Surface(color=Surface2,shape=RoundedCornerShape(12.dp),modifier=modifier){Column(Modifier.padding(10.dp)){Text(label,color=Muted,fontSize=9.sp);Text(value?.let(::money)?:"—",fontWeight=FontWeight.Black,fontSize=13.sp,maxLines=1)}}

@Composable private fun MarketContextCard(context:StockbitMarketContext) {
    CardBlock{
        Text("STOCKBIT LIVE CONTEXT",color=Lumi,fontWeight=FontWeight.Bold,fontSize=13.sp)
        DetailRow("Best Bid",context.bestBid?.let(::money)?:"—");DetailRow("Best Offer",context.bestOffer?.let(::money)?:"—");DetailRow("Spread",listOfNotNull(context.spreadPercent?.let{"%.2f%%".format(it)},context.spreadTicks?.let{"$it fraksi"}).joinToString(" • ").ifBlank{"—"})
        DetailRow("UMA",context.uma?.let{if(it)"TERDETEKSI" else "Tidak terdeteksi"}?:"Belum tersedia",if(context.uma==true)Bad else Muted)
        if(context.specialNotations.isNotEmpty())DetailRow("Notasi",context.specialNotations.joinToString())
        if(context.corporateActions.isNotEmpty()){Text("Corporate action",color=Muted,fontSize=10.sp,modifier=Modifier.padding(top=8.dp));context.corporateActions.forEach{Text("• $it",fontSize=11.sp)}}
        if(context.shareholderSummary.isNotEmpty()){Text("Shareholder composition",color=Muted,fontSize=10.sp,modifier=Modifier.padding(top=8.dp));context.shareholderSummary.forEach{Text("• $it",fontSize=11.sp)}}
        if(context.tradeBookSummary.isNotEmpty())DetailRow("Trade book",context.tradeBookSummary.joinToString(" • "))
        context.warnings.forEach{Text("• $it",color=Warn,fontSize=9.sp,modifier=Modifier.padding(top=4.dp))}
        Text("UMA, spread, tradability, dan corporate action adalah gate wajib ranking. Shareholder dan trade book bersifat informatif.",color=Muted,fontSize=9.sp,modifier=Modifier.padding(top=8.dp))
    }
}

@Composable private fun HistoryScreen(vm:MainViewModel,onDetail:(String)->Unit) {
    val rows by vm.signals.collectAsStateWithLifecycle(); val perf by vm.performance.collectAsStateWithLifecycle()
    var source by rememberSaveable{mutableStateOf("TELEGRAM")};val sources=listOf("TELEGRAM","SEMUA STRATEGI")
    var tab by rememberSaveable{mutableStateOf("SEMUA")}; val tabs=listOf("SEMUA","HOLDING","TP","SL","WAITING","EXPIRED")
    val sourceRows=if(source=="TELEGRAM")rows.filter{it.telegramSent}else rows
    val filtered=sourceRows.filter{tab=="SEMUA"||when(tab){"TP"->it.status==SignalStatus.TAKE_PROFIT;"SL"->it.status==SignalStatus.STOP_LOSS;"WAITING"->it.status==SignalStatus.WAITING_ENTRY;else->it.status.name==tab}}
    val groups=filtered.groupBy{it.ticker to it.referenceTradingDate}.values.map{items->HistoryTickerGroup(items.first().ticker,items.first().companyName,items.first().referenceTradingDate,items.sortedBy{StrategyType.fromStored(it.setup).number})}.sortedByDescending{g->g.signals.maxOf{it.signalTimestamp}}
    LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item{Header();Spacer(Modifier.height(8.dp));SectionTitle("HISTORY","Satu saham ditampilkan sekali per tanggal, seluruh strateginya tetap terlihat")}
        item{PerformanceGrid(perf)}
        item{TabRow(selectedTabIndex=sources.indexOf(source),containerColor=Surface,divider={}){sources.forEach{s->Tab(selected=source==s,onClick={source=s},text={Text(s,fontSize=11.sp,fontWeight=FontWeight.Bold)})}}}
        item{Text(if(source=="TELEGRAM")"Hanya sinyal yang Anda pilih dan berhasil dikirim ke Telegram." else "Seluruh kandidat Top 5 dari 10 strategi, termasuk yang belum dikirim ke Telegram.",color=Muted,fontSize=10.sp)}
        item{ScrollableTabRow(selectedTabIndex=tabs.indexOf(tab),containerColor=Navy,edgePadding=0.dp){tabs.forEach{t->Tab(selected=tab==t,onClick={tab=t},text={Text(t)})}}}
        if(groups.isEmpty())item{EmptyCard("Belum ada record pada sumber $source dengan status $tab.")}
        items(groups,key={"${it.ticker}-${it.referenceDate}"}){group->HistoryTickerCard(group,onDetail,source=="TELEGRAM")}
    }
}

@Composable private fun HistoryTickerCard(group:HistoryTickerGroup,onDetail:(String)->Unit,telegramView:Boolean) {
    Card(colors=CardDefaults.cardColors(containerColor=Surface),shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment=Alignment.Top){Column(Modifier.weight(1f)){Text(group.ticker.removeSuffix(".JK"),fontSize=22.sp,fontWeight=FontWeight.Black);Text(group.companyName,color=Muted,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis)};Text(date(group.referenceDate*1000),color=Muted,fontSize=10.sp)}
            Text("Masuk ${group.signals.size} strategi",color=Lumi,fontSize=10.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=6.dp,bottom=6.dp))
            group.signals.forEach { signal ->
                val strategy=StrategyType.fromStored(signal.setup)
                val actualEntry=signal.entryTriggeredPrice
                Surface(color=Surface2,shape=RoundedCornerShape(13.dp),modifier=Modifier.fillMaxWidth().padding(top=6.dp).clickable{onDetail(signal.uuid)}) {
                    Column(Modifier.padding(11.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically){Text("S${strategy.number} • ${strategy.label}",fontWeight=FontWeight.Bold,fontSize=11.sp,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis);StatusBadge(signal.status)}
                        Text(strategy.description,color=Muted,fontSize=9.sp,modifier=Modifier.padding(top=2.dp))
                        Row(Modifier.fillMaxWidth().padding(top=7.dp),horizontalArrangement=Arrangement.SpaceBetween){Metric("Entry",actualEntry?.let{"Rp${it.toInt().formatIdr()} ✓"}?:"Rp${signal.entryLow.formatIdr()}–${signal.entryHigh.formatIdr()}",if(actualEntry!=null)Good else Color.White);Metric("TP","Rp${signal.takeProfit.formatIdr()}${if(signal.status==SignalStatus.TAKE_PROFIT)" ✓" else ""}",if(signal.status==SignalStatus.TAKE_PROFIT)Good else Color.White);Metric("SL","Rp${signal.stopLoss.formatIdr()}${if(signal.status==SignalStatus.STOP_LOSS)" ✓" else ""}",if(signal.status==SignalStatus.STOP_LOSS)Bad else Color.White)}
                        if(telegramView)Text("Signal dipilih dan terkirim",color=Good,fontSize=9.sp,modifier=Modifier.padding(top=6.dp))
                    }
                }
            }
        }
    }
}

@Composable private fun SignalDetailScreen(vm:MainViewModel,uuid:String,onBack:()->Unit) {
    val rows by vm.signals.collectAsStateWithLifecycle(); val signal=rows.firstOrNull{it.uuid==uuid}; val chart by vm.chart.collectAsStateWithLifecycle()
    LaunchedEffect(signal?.ticker){signal?.let{vm.loadChart(it.ticker)}}
    LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item{IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)}}
        if(signal==null)item{ErrorCard("Signal tidak ditemukan.")}
        signal?.let{s->
            item{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(s.ticker.removeSuffix(".JK"),fontSize=30.sp,fontWeight=FontWeight.Black);Text(s.companyName,color=Muted)};StatusBadge(s.status)}}
            item{CardBlock{DetailRow("Signal Date",date(s.signalTimestamp));DetailRow("Reference Close","Rp${s.referenceClose.toInt().formatIdr()}");DetailRow("Entry",s.entryTriggeredPrice?.let{"Rp${it.toInt().formatIdr()} ✓ TERSENTUH"}?:"Rp${s.entryLow.formatIdr()} – Rp${s.entryHigh.formatIdr()}",if(s.entryTriggeredPrice!=null)Good else Color.White);DetailRow("Take Profit","Rp${s.takeProfit.formatIdr()}${if(s.status==SignalStatus.TAKE_PROFIT)" ✓ TERCAPAI" else ""}",if(s.status==SignalStatus.TAKE_PROFIT)Good else Color.White);DetailRow("Stop Loss","Rp${s.stopLoss.formatIdr()}${if(s.status==SignalStatus.STOP_LOSS)" ✓ TERSENTUH" else ""}",if(s.status==SignalStatus.STOP_LOSS)Bad else Color.White);DetailRow("Risk/Reward","1 : ${"%.2f".format(s.riskReward)}")}}
            item{Text("PRICE CHART",color=Muted,fontWeight=FontWeight.Bold);CardBlock{if(chart==null)LinearProgressIndicator(Modifier.fillMaxWidth())else PriceChart(chart!!.candles.takeLast(90),s)}}
            item{CardBlock{Text("SCORING",fontWeight=FontWeight.Bold);DetailRow("Broker",s.brokerScore?.let{"${it.toInt()}/100"}?:"Unavailable");DetailRow("Volume","${s.volumeScore.toInt()}/100");DetailRow("Anomaly","${s.anomalyScore.toInt()}/100");DetailRow("Technical","${s.technicalScore.toInt()}/100");DetailRow("Distribution Risk","${s.distributionScore.toInt()}/100");DetailRow("Chasing Risk","${s.chasingRisk.toInt()}/100");DetailRow("Final Score","${s.finalScore.toInt()}/100")}}
            item{CardBlock{Text("WHY LUMI PICKED THIS",fontWeight=FontWeight.Bold);s.reasons.lines().forEach{Text("• $it",modifier=Modifier.padding(top=5.dp))}}}
            item{CardBlock{DetailRow("Entry Trigger",s.entryTriggeredDate?.let(::date)?:"—");DetailRow("Entry Aktual",s.entryTriggeredPrice?.let{"Rp${it.toInt().formatIdr()}"}?:"—",if(s.entryTriggeredPrice!=null)Good else Color.White);DetailRow("Exit Date",s.exitDate?.let(::date)?:"—");DetailRow("Result",s.result?:"—",when(s.status){SignalStatus.TAKE_PROFIT->Good;SignalStatus.STOP_LOSS->Bad;else->Color.White});DetailRow("Gain/Loss",s.gainLoss?.let(::signedPct)?:"—",s.gainLoss?.let{if(it>=0)Good else Bad}?:Color.White)}}
        }
    }
}

@Composable private fun SettingsScreen(vm:MainViewModel) {
    val connections by vm.connections.collectAsStateWithLifecycle()
    val stored by vm.storedCredentials.collectAsStateWithLifecycle()
    var stockToken by remember{mutableStateOf("")};var botToken by remember{mutableStateOf("")};var chatId by remember{mutableStateOf("")};var confirmReset by remember{mutableStateOf(false)};var showNotices by remember{mutableStateOf(false)}
    val context=LocalContext.current;val scope= rememberCoroutineScope();var backupText by remember{mutableStateOf<String?>(null)}
    val createBackup= rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->uri?.let{u->backupText?.let{context.contentResolver.openOutputStream(u)?.use{x->x.write(it.toByteArray())}}}}
    val restoreBackup= rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->uri?.let{u->runCatching{context.contentResolver.openInputStream(u)?.bufferedReader()?.use{it.readText()}.orEmpty()}.onSuccess(vm::restoreBackup)}}
    val stockbitLogin=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){r->if(r.resultCode==android.app.Activity.RESULT_OK)vm.refreshStockbitConnection()}
    LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item{Header();Spacer(Modifier.height(8.dp));SectionTitle("SETTINGS","Credential disimpan terenkripsi di perangkat")}
        item{SettingsGroup("DATA SOURCE"){DetailRow("IDX Universe","Live Indonesia market scanner + cache 7 hari");DetailRow("Stockbit Market","OHLCV, nilai, frekuensi, audit, orderbook, broker flow");DetailRow("Stockbit Portfolio","Equity, cash, settlement, posisi, order, history • baca-saja");DetailRow("Portfolio Status",connections.brokerage);DetailRow("Telegram","Opsional");Text("Market login dan sesi Stockbit Sekuritas adalah dua sesi berbeda. Portfolio dibuka dari tab Portfolio dengan PIN sekali pakai; PIN tidak disimpan. Konektor ini eksperimental, bukan SDK resmi.",color=Warn,fontSize=12.sp)}}
        item{SettingsGroup("KONEKSI STOCKBIT"){Button(onClick={stockbitLogin.launch(Intent(context,StockbitLoginActivity::class.java))},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Info,null);Spacer(Modifier.width(8.dp));Text("LOGIN VIA WEBVIEW")};Text("Access + refresh token dari respons autentikasi ditangkap otomatis dan disimpan terenkripsi. Password, CAPTCHA, dan PIN trading tidak dibaca.",color=Muted,fontSize=11.sp);DetailRow("Status",connections.stockbit);DetailRow("Token tersimpan",stored.stockbitToken?:"—");SecretField(stockToken,{stockToken=it},"Refresh Token Baru (opsional)");OutlinedButton(onClick={if(stockToken.isBlank())vm.refreshStockbitConnection() else vm.testStockbit(stockToken)},enabled=stockToken.isNotBlank()||stored.stockbitToken!=null,modifier=Modifier.fillMaxWidth()){Text(if(stockToken.isBlank()&&stored.stockbitToken!=null)"VALIDASI TOKEN TERSIMPAN" else "SIMPAN & TEST CONNECTION")};TextButton(onClick={stockToken="";android.webkit.CookieManager.getInstance().removeAllCookies(null);android.webkit.WebStorage.getInstance().deleteAllData();vm.logoutStockbit()},modifier=Modifier.fillMaxWidth()){Text("LOGOUT / HAPUS TOKEN")};Surface(color=Warn.copy(alpha=.10f),shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth()){Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Lock,null,tint=Warn);Spacer(Modifier.width(9.dp));Column{Text("BROKER HISTORY BERTINGKAT",color=Warn,fontWeight=FontWeight.Bold);Text("Ringkasan 10 sesi memeringkat pasar; history harian diwajibkan untuk strategi 2, 3, 5, dan 7. Skor teknikal tetap dapat dilihat untuk diagnosis, tetapi sinyal tanpa broker tidak masuk Top 5.",color=Muted,fontSize=11.sp)}}}}}
        item{SettingsGroup("TELEGRAM SETTINGS"){DetailRow("Status",connections.telegram);DetailRow("Bot Token tersimpan",stored.telegramToken?:"—");DetailRow("Chat ID tersimpan",stored.telegramChatId?:"—");SecretField(botToken,{botToken=it},"Bot Token Baru (kosong = pakai tersimpan)");LumiField(chatId,{chatId=it},"Chat ID Baru (kosong = pakai tersimpan)");Button(onClick={vm.testTelegram(botToken,chatId)},enabled=(botToken.isNotBlank()||stored.telegramToken!=null)&&(chatId.isNotBlank()||stored.telegramChatId!=null),modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Send,null);Spacer(Modifier.width(8.dp));Text("TEST TELEGRAM")};TextButton(onClick={botToken="";chatId="";vm.logoutTelegram()},enabled=stored.telegramToken!=null||stored.telegramChatId!=null,modifier=Modifier.fillMaxWidth()){Text("HAPUS CREDENTIAL TELEGRAM")}}}
        item{SettingsGroup("MESIN 10 STRATEGI"){Text("Tidak ada pengaturan liquidity atau risk management. Filter wajib dan ambang tiap strategi mengikuti spesifikasi tetap.",color=Muted,fontSize=12.sp);Text("Entry, TP, dan SL dihitung otomatis dari trigger, support/resistance, ATR, serta struktur khusus strategi. Kriteria inti menjadi hard gate; skor tinggi tidak dapat menutupi pola utama yang gagal.",color=Lumi,fontSize=11.sp,fontWeight=FontWeight.Bold);Text("Seluruh filter strategi memakai OHLCV, nilai transaksi, frekuensi, dan broker summary Stockbit. Orderbook/spread, UMA/notasi, corporate action, shareholder, dan trade book tampil sebagai konteks tambahan; field yang belum tervalidasi tidak memengaruhi ranking.",color=Warn,fontSize=11.sp)}}
        item{SettingsGroup("HISTORY"){OutlinedButton(onClick={scope.launch{backupText=vm.exportBackup();createBackup.launch("Lumi-Signal-Backup.json")}},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Share,null);Spacer(Modifier.width(8.dp));Text("EXPORT / BACKUP JSON")};OutlinedButton(onClick={restoreBackup.launch("application/json")},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Refresh,null);Spacer(Modifier.width(8.dp));Text("RESTORE BACKUP")};OutlinedButton(onClick={confirmReset=true},colors=ButtonDefaults.outlinedButtonColors(contentColor=Bad),modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Delete,null);Spacer(Modifier.width(8.dp));Text("RESET HISTORY")}}}
        item{SettingsGroup("ABOUT"){Image(painterResource(R.drawable.lumi_logo),"Logo",Modifier.size(72.dp).align(Alignment.CenterHorizontally));Text("Lumi Signal - IDX Screener",fontWeight=FontWeight.Bold,modifier=Modifier.align(Alignment.CenterHorizontally));Text("10-Strategy Technical–Bandarmology Screener\nVersion ${BuildConfig.VERSION_NAME} • build ${BuildConfig.VERSION_CODE}\nRelease: READ-ONLY PORTFOLIO",color=Muted,modifier=Modifier.align(Alignment.CenterHorizontally));Text("Stockbit connector adapted from stockbit-mcp (MIT). Bukan integrasi resmi Stockbit.",color=Muted,fontSize=11.sp);TextButton(onClick={showNotices=true},modifier=Modifier.align(Alignment.CenterHorizontally)){Text("THIRD-PARTY NOTICES")}}}
    }
    if(confirmReset)AlertDialog(onDismissRequest={confirmReset=false},title={Text("Reset seluruh history?")},text={Text("Signal, hasil screening, audit, cache, settings, dan activity log akan dihapus. Credential terenkripsi tidak ikut dihapus.")},confirmButton={TextButton(onClick={confirmReset=false;vm.resetHistory()},colors=ButtonDefaults.textButtonColors(contentColor=Bad)){Text("Reset")}},dismissButton={TextButton(onClick={confirmReset=false}){Text("Batal")}})
    if(showNotices)AlertDialog(onDismissRequest={showNotices=false},title={Text("Third-Party Notices")},text={Text(context.resources.openRawResource(R.raw.third_party_notices).bufferedReader().use{it.readText()},modifier=Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()),fontSize=11.sp)},confirmButton={TextButton(onClick={showNotices=false}){Text("Tutup")}})
}

@Composable private fun CardBlock(content:@Composable ColumnScope.()->Unit)=Card(colors=CardDefaults.cardColors(containerColor=Surface),shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),content=content)}
@Composable private fun StrategyDropdown(selected:StrategyType,onSelected:(StrategyType)->Unit){var expanded by remember{mutableStateOf(false)};Box(Modifier.fillMaxWidth()){OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth().heightIn(min=66.dp)){Icon(Icons.Default.List,null);Spacer(Modifier.width(9.dp));Column(Modifier.weight(1f),horizontalAlignment=Alignment.Start){Text("Strategi ${selected.number} — ${selected.label}",fontWeight=FontWeight.Bold,fontSize=12.sp);Text(selected.description,color=Muted,fontSize=10.sp,maxLines=2)};Icon(Icons.Default.ArrowDropDown,null)};DropdownMenu(expanded=expanded,onDismissRequest={expanded=false},modifier=Modifier.fillMaxWidth(.94f)){StrategyType.entries.forEach{s->DropdownMenuItem(text={Column{Text("${s.number}. ${s.label}",fontWeight=if(s==selected)FontWeight.Bold else FontWeight.Normal);Text(s.description,color=Muted,fontSize=10.sp,maxLines=2)}},leadingIcon={if(s==selected)Icon(Icons.Default.Check,null,tint=Good)else Icon(Icons.Default.Star,null,tint=Muted)},onClick={onSelected(s);expanded=false})}}}}
@Composable private fun SettingsGroup(title:String,content:@Composable ColumnScope.()->Unit)=CardBlock{Text(title,color=Lumi,fontWeight=FontWeight.Bold,fontSize=13.sp);Spacer(Modifier.height(12.dp));Column(verticalArrangement=Arrangement.spacedBy(10.dp),content=content)}
@Composable private fun SectionTitle(title:String,subtitle:String){Column{Text(title,fontSize=18.sp,fontWeight=FontWeight.Black);Text(subtitle,color=Muted,fontSize=12.sp)}}
private fun stockbitTierLabel(tier:StockbitAccountTier)=when(tier){StockbitAccountTier.PRO->"PRO • 10 strategi terbuka";StockbitAccountTier.NON_PRO->"NON-PRO • strategi terkunci";StockbitAccountTier.UNKNOWN->"Belum terverifikasi"}
@Composable private fun StatusLine(label:String,status:String){val ok=status=="Connected"||status=="Available"||status.startsWith("PRO •");Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(8.dp).background(if(ok)Good else if(status=="Checking"||status=="Configured"||status=="Unknown"||status=="Empty"||status=="Belum terverifikasi")Warn else Bad,RoundedCornerShape(8.dp)));Spacer(Modifier.width(9.dp));Text(label,Modifier.weight(1f));Text(status,color=Muted)}}
@Composable private fun EmptyCard(text:String)=CardBlock{Icon(Icons.Default.Info,null,tint=Muted);Spacer(Modifier.height(8.dp));Text(text,color=Muted)}
@Composable private fun ErrorCard(text:String)=Card(colors=CardDefaults.cardColors(containerColor=Bad.copy(alpha=.12f)),modifier=Modifier.fillMaxWidth()){Text(text,color=Bad,modifier=Modifier.padding(14.dp))}
@Composable private fun WarningCard(text:String)=Card(colors=CardDefaults.cardColors(containerColor=Warn.copy(alpha=.12f)),modifier=Modifier.fillMaxWidth()){Text(text,color=Warn,modifier=Modifier.padding(14.dp))}
@Composable private fun ProgressCard(stage:String,current:Int,total:Int,onCancel:()->Unit)=CardBlock{Text("Scanning IDX Market",fontWeight=FontWeight.Bold);Text(stage.ifBlank{"Memulai screening..."},color=Muted,fontSize=13.sp);Spacer(Modifier.height(10.dp));if(total<=0)LinearProgressIndicator(modifier=Modifier.fillMaxWidth())else LinearProgressIndicator(progress={current.toFloat()/total},modifier=Modifier.fillMaxWidth());Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text(if(total>0)"$current / $total" else "Menunggu proses aktif",color=Muted,fontSize=12.sp);TextButton(onClick=onCancel){Text("BATALKAN",color=Bad)}}}

@Composable private fun ScreeningCoverageCard(run:ScreeningRunEntity) {
    val complete=run.status=="COMPLETE"
    CardBlock {
        Row(verticalAlignment=Alignment.CenterVertically){Icon(if(complete)Icons.Default.CheckCircle else Icons.Default.Warning,null,tint=if(complete)Good else Warn);Spacer(Modifier.width(8.dp));Column{Text(if(complete)"CAKUPAN PASAR LENGKAP" else "CAKUPAN PASAR PARSIAL",color=if(complete)Good else Warn,fontWeight=FontWeight.Bold);Text(run.message?:run.stage,color=Muted,fontSize=10.sp)}}
        Spacer(Modifier.height(10.dp))
        DetailRow("Universe ditemukan",run.universeDiscovered.toString())
        DetailRow("OHLCVF valid / gagal","${run.technicalValid} / ${run.technicalFailed}")
        DetailRow("Ticker stale dikeluarkan",run.staleExcluded.toString())
        DetailRow("Broker seed kandidat valid / gagal","${run.brokerSeedValid} / ${run.brokerSeedFailed}")
        DetailRow("Broker detail valid / gagal","${run.detailValid} / ${run.detailFailed}")
        run.completedAt?.let{done->val secs=((done-run.createdAt)/1000).coerceAtLeast(0);DetailRow("Durasi screening","%d:%02d".format(secs/60,secs%60))}
        if(!complete)Text("Top 5 bersifat sementara bila ada sumber kritis yang gagal; Lumi tidak mengklaim hasil ini sebagai keseluruhan pasar.",color=Warn,fontSize=10.sp,modifier=Modifier.padding(top=8.dp))
    }
}
@Composable private fun ScoreCircle(score:Double){Box(Modifier.size(54.dp).background(Lumi.copy(alpha=.13f),RoundedCornerShape(27.dp)),contentAlignment=Alignment.Center){Text(score.toInt().toString(),color=Lumi,fontWeight=FontWeight.Black,fontSize=19.sp)}}
@Composable private fun Metric(label:String,value:String,color:Color=Color.White){Column{Text(label,color=Muted,fontSize=10.sp);Text(value,color=color,fontWeight=FontWeight.Bold,fontSize=12.sp)}}
@Composable private fun MiniScore(label:String,value:Double?){Column(horizontalAlignment=Alignment.CenterHorizontally){Text(value?.toInt()?.toString()?:"—",fontWeight=FontWeight.Bold,color=if(value==null)Muted else Lumi);Text(label,color=Muted,fontSize=9.sp)}}
@Composable private fun PerformanceGrid(p:com.lumisignal.idxscreener.engine.Performance){CardBlock{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Perf("Total",p.total.toString());Perf("Finished",p.finished.toString());Perf("Win",p.wins.toString());Perf("Loss",p.losses.toString())};HorizontalDivider(Modifier.padding(vertical=12.dp),color=Surface2);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Perf("Win Rate","${"%.1f".format(p.winRate)}%",Good);Perf("Cumulative","${"%+.1f".format(p.cumulativeReturn)}%",if(p.cumulativeReturn>=0)Good else Bad);Perf("Compounded","${"%+.1f".format(p.compoundedReturn)}%",if(p.compoundedReturn>=0)Good else Bad)}}}
@Composable private fun Perf(label:String,value:String,color:Color=Color.White){Column(horizontalAlignment=Alignment.CenterHorizontally){Text(value,fontSize=18.sp,fontWeight=FontWeight.Black,color=color);Text(label,color=Muted,fontSize=10.sp)}}
@Composable private fun QuoteCard(s:MarketSeries) {
    val last=s.candles.last();val prev=s.candles.dropLast(1).lastOrNull();val change=prev?.let{(last.close/it.close-1)*100}?:0.0
    Card(colors=CardDefaults.cardColors(containerColor=Surface),shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment=Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(s.ticker.removeSuffix(".JK"),fontSize=28.sp,fontWeight=FontWeight.Black)
                    Text(s.companyName,color=Muted,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment=Alignment.End) {
                    Text("Rp${last.close.toInt().formatIdr()}",fontSize=23.sp,fontWeight=FontWeight.Black)
                    Surface(color=(if(change>=0)Good else Bad).copy(alpha=.14f),shape=RoundedCornerShape(18.dp)) {
                        Text(signedPct(change),color=if(change>=0)Good else Bad,fontWeight=FontWeight.Bold,modifier=Modifier.padding(horizontal=10.dp,vertical=4.dp))
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical=14.dp),color=Surface2)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                QuoteMetric("OPEN",last.open.toInt().formatIdr(),Modifier.weight(1f))
                QuoteMetric("HIGH",last.high.toInt().formatIdr(),Modifier.weight(1f))
                QuoteMetric("LOW",last.low.toInt().formatIdr(),Modifier.weight(1f))
                QuoteMetric("VOLUME",compactVolume(last.volume),Modifier.weight(1.18f))
            }
            Row(Modifier.padding(top=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Default.DateRange,null,tint=Muted,modifier=Modifier.size(14.dp));Spacer(Modifier.width(6.dp))
                Text("Sesi selesai ${date(last.epochSeconds*1000)}",color=Muted,fontSize=11.sp,modifier=Modifier.weight(1f))
                Text("STOCKBIT",color=Lumi,fontSize=10.sp,fontWeight=FontWeight.Bold)
            }
        }
    }
}

@Composable private fun QuoteMetric(label:String,value:String,modifier:Modifier=Modifier) {
    Surface(color=Surface2,shape=RoundedCornerShape(12.dp),modifier=modifier) {
        Column(Modifier.padding(horizontal=8.dp,vertical=9.dp)) {
            Text(label,color=Muted,fontSize=8.sp,fontWeight=FontWeight.Bold,maxLines=1)
            Text(value,fontSize=12.sp,fontWeight=FontWeight.Bold,maxLines=1)
        }
    }
}

@Composable private fun StrategyScoreOverview(analyses:List<Candidate>,selected:StrategyType,onSelected:(StrategyType)->Unit) {
    CardBlock {
        Text("SKOR 10 STRATEGI",color=Lumi,fontWeight=FontWeight.Bold,fontSize=13.sp)
        Text("Pilih salah satu strategi untuk membuka alasan, indikator, dan chart khususnya.",color=Muted,fontSize=10.sp,modifier=Modifier.padding(top=3.dp,bottom=10.dp))
        analyses.sortedBy { it.strategy.number }.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().padding(bottom=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                pair.forEach { candidate ->
                    val active=candidate.strategy==selected
                    val tone=if(candidate.passed)Good else if(!candidate.calculationComplete)Warn else Muted
                    val scoreText=if(candidate.calculationComplete)candidate.strategyScore.toInt().toString() else "N/A"
                    Surface(color=if(active)Lumi.copy(alpha=.18f) else Surface2,shape=RoundedCornerShape(13.dp),border=if(active)androidx.compose.foundation.BorderStroke(1.dp,Lumi) else null,modifier=Modifier.weight(1f).clickable{onSelected(candidate.strategy)}) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically){Text("S${candidate.strategy.number}",color=if(active)Lumi else tone,fontWeight=FontWeight.Black,fontSize=11.sp);Spacer(Modifier.weight(1f));Text(scoreText,color=tone,fontWeight=FontWeight.Black,fontSize=if(candidate.calculationComplete)20.sp else 13.sp)}
                            Text(candidate.strategy.label,fontWeight=FontWeight.Bold,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text("${candidate.matchedCriteria}/10 • ${if(candidate.calculationComplete)candidate.strategy.description else "BELUM LENGKAP"}",color=if(candidate.calculationComplete)Muted else Warn,fontSize=8.sp,maxLines=2,modifier=Modifier.padding(top=3.dp))
                        }
                    }
                }
                if(pair.size==1)Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable private fun SearchAnalysisCard(candidate:Candidate,audit:SignalEntity?=null) {
    Card(colors=CardDefaults.cardColors(containerColor=Surface),shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("STRATEGI ${candidate.strategy.number}",color=Muted,fontSize=11.sp,fontWeight=FontWeight.Bold)
                Text(candidate.strategy.label,color=Lumi,fontWeight=FontWeight.Bold,fontSize=17.sp)
                Text(candidate.strategy.description,color=Muted,fontSize=10.sp)
            }
            Column(horizontalAlignment=Alignment.End) {
                Text("SCORE STRATEGI",color=Muted,fontSize=10.sp)
                Text(if(candidate.calculationComplete)"${candidate.strategyScore.toInt()}/100" else "BELUM LENGKAP",color=if(candidate.calculationComplete)Lumi else Warn,fontSize=if(candidate.calculationComplete)24.sp else 12.sp,fontWeight=FontWeight.Black)
                Text("${candidate.matchedCriteria}/10 cocok",color=if(candidate.passed)Good else Warn,fontSize=10.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            ScoreTile("BROKER",candidate.scores.broker,Modifier.weight(1f))
            ScoreTile("VOLUME",candidate.scores.volume,Modifier.weight(1f))
            ScoreTile("STRATEGI",candidate.strategyScore.takeIf { candidate.calculationComplete },Modifier.weight(1f))
            ScoreTile("COCOK",candidate.matchedCriteria*10.0,Modifier.weight(1f))
        }
        if(!candidate.calculationComplete) {
            Text("Perhitungan strategi belum lengkap. Field yang tidak tersedia ditampilkan sebagai N/A, bukan skor 0.",color=Warn,fontSize=11.sp,modifier=Modifier.padding(top=8.dp))
        } else if(!candidate.operationalGatesComplete) {
            Text("Skor strategi tersedia, tetapi gate operasional Stockbit belum lengkap sehingga hasil bersifat provisional.",color=Warn,fontSize=11.sp,modifier=Modifier.padding(top=8.dp))
        }
        BrokerFlowDetails(candidate.brokerAnalysis)
        HorizontalDivider(Modifier.padding(vertical=16.dp),color=Surface2)
        Text("FLOW & ACTIVITY",color=Muted,fontSize=11.sp,fontWeight=FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            AnalysisMetric("RVOL 5D","${"%.2f".format(candidate.rvol5)}x",Modifier.weight(1f))
            AnalysisMetric("RVOL 20D","${"%.2f".format(candidate.rvol20)}x",Modifier.weight(1f))
            AnalysisMetric("VALUE","${"%.2f".format(candidate.valueExpansion)}x",Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            AnalysisMetric("VOL Z","${if(candidate.volumeZScore>=0) "+" else ""}${"%.2f".format(candidate.volumeZScore)}",Modifier.weight(1f))
            AnalysisMetric("DISTRIBUSI","${candidate.scores.distributionRisk.toInt()}/100",Modifier.weight(1f),if(candidate.scores.distributionRisk>=60)Bad else Good)
            AnalysisMetric("CHASING","${candidate.scores.chasingRisk.toInt()}/100",Modifier.weight(1f),if(candidate.scores.chasingRisk>=60)Bad else Good)
        }
        Text(chasingSummary(candidate.scores.chasingRisk),color=Muted,fontSize=10.sp,modifier=Modifier.padding(top=7.dp))
        Text("INDIKATOR KHUSUS STRATEGI",color=Muted,fontSize=11.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=14.dp,bottom=8.dp))
        candidate.indicators.chunked(2).forEach { row -> Row(Modifier.fillMaxWidth().padding(bottom=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) { row.forEach { metric -> AnalysisMetric(metric.label,metric.value,Modifier.weight(1f),when(metric.tone){"GOOD"->Good;"BAD"->Bad;else->Color.White}) };if(row.size==1)Spacer(Modifier.weight(1f)) } }
        HorizontalDivider(Modifier.padding(vertical=16.dp),color=Surface2)
        Text("TRADE PLAN",color=Muted,fontSize=11.sp,fontWeight=FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        val auditedEntry=audit?.entryTriggeredPrice
        val tpHit=audit?.status==SignalStatus.TAKE_PROFIT
        val slHit=audit?.status==SignalStatus.STOP_LOSS
        TradePlanTile("ENTRY",auditedEntry?.let{"Rp${it.toInt().formatIdr()} ✓ TERSENTUH"}?:"Rp${candidate.tradePlan.entryLow.formatIdr()} – Rp${candidate.tradePlan.entryHigh.formatIdr()}",if(auditedEntry!=null)Good else Lumi)
        Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TradePlanTile("TAKE PROFIT","Rp${candidate.tradePlan.takeProfit.formatIdr()}${if(tpHit)" ✓ TERCAPAI" else ""}",if(tpHit)Good else Color.White,Modifier.weight(1f))
            TradePlanTile("STOP LOSS","Rp${candidate.tradePlan.stopLoss.formatIdr()}${if(slHit)" ✓ TERSENTUH" else ""}",if(slHit)Bad else Color.White,Modifier.weight(1f))
        }
        Surface(color=Warn.copy(alpha=.10f),shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth().padding(top=8.dp)) {
            Row(Modifier.padding(11.dp),horizontalArrangement=Arrangement.SpaceBetween){Text("Risk / Reward",color=Muted);Text("1 : ${"%.2f".format(candidate.tradePlan.riskReward)}",color=Warn,fontWeight=FontWeight.Bold)}
        }
        HorizontalDivider(Modifier.padding(vertical=16.dp),color=Surface2)
        Text("AUDIT FILTER STRATEGI",color=Muted,fontSize=11.sp,fontWeight=FontWeight.Bold)
        candidate.criteria.forEach { criterion ->
            val color=when(criterion.state){CriterionState.MATCH->Good;CriterionState.MISS->Bad;CriterionState.DATA_UNAVAILABLE->Warn}
            Row(Modifier.fillMaxWidth().padding(top=7.dp)){Text(when(criterion.state){CriterionState.MATCH->"✓";CriterionState.MISS->"✕";CriterionState.DATA_UNAVAILABLE->"?"},color=color,fontWeight=FontWeight.Black,modifier=Modifier.width(24.dp));Column(Modifier.weight(1f)){Text(criterion.label,fontSize=11.sp,fontWeight=FontWeight.Medium);Text(criterion.evidence,color=Muted,fontSize=10.sp)}}
        }
        HorizontalDivider(Modifier.padding(vertical=16.dp),color=Surface2)
        Text("WHY LUMI RATES THIS",color=Muted,fontSize=11.sp,fontWeight=FontWeight.Bold)
        Surface(color=Surface2,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth().padding(top=8.dp)) {
            Column(Modifier.padding(12.dp)) {
                candidate.why.forEach { reason ->
                    Row(Modifier.padding(vertical=3.dp)) {
                        Text("•",color=Lumi,fontWeight=FontWeight.Bold)
                        Spacer(Modifier.width(7.dp))
                        Text(reason,fontSize=12.sp,modifier=Modifier.weight(1f))
                    }
                }
            }
        }
    } }
}

@Composable private fun BrokerFlowDetails(broker:BrokerAnalysis) {
    if(!broker.available)return
    val periods=listOf(1,3,5,10).filter{broker.periodTopBuyers.containsKey(it)||broker.periodTopSellers.containsKey(it)}
    var selectedPeriod by remember(broker.periodTopBuyers,broker.periodTopSellers){mutableIntStateOf(if(1 in periods)1 else periods.firstOrNull()?:10)}
    val selectedBuyers=broker.periodTopBuyers[selectedPeriod]?:broker.topBuyers
    val selectedSellers=broker.periodTopSellers[selectedPeriod]?:broker.topSellers
    Spacer(Modifier.height(16.dp))
    Text("BROKER DOMINANCE • TOP-5 IMBALANCE",color=Muted,fontSize=11.sp,fontWeight=FontWeight.Bold)
    if(broker.periodNetBuy.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            listOf(1,3,5,10).forEach { days -> BrokerPeriodTile("${days}D",broker.periodNetBuy[days],Modifier.weight(1f)) }
        }
        Text("Selisih nilai Top-5 net buyer dan Top-5 net seller. Total seluruh broker tidak dipakai karena secara akuntansi selalu mendekati nol.",color=Muted,fontSize=9.sp,modifier=Modifier.padding(top=6.dp))
    }
    if(periods.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().padding(top=10.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            periods.forEach { days -> FilterChip(selected=selectedPeriod==days,onClick={selectedPeriod=days},label={Text("${days}D",fontSize=10.sp)},modifier=Modifier.weight(1f)) }
        }
    }
    if(selectedBuyers.isNotEmpty()) {
        BrokerSectionTitle("TOP NET BUYER • ${selectedPeriod}D",Icons.Default.KeyboardArrowUp,Good)
        selectedBuyers.take(5).forEachIndexed { index,item -> BrokerItemRow(index+1,item,Good) }
    }
    if(selectedSellers.isNotEmpty()) {
        BrokerSectionTitle("TOP NET SELLER • ${selectedPeriod}D",Icons.Default.KeyboardArrowDown,Bad)
        selectedSellers.take(5).forEachIndexed { index,item -> BrokerItemRow(index+1,item,Bad) }
    }
    val foreign1d=broker.foreignPeriodNetBuy[1]
    val foreign10d=broker.foreignPeriodNetBuy[10]?:broker.foreignNetBuy
    Surface(color=Lumi.copy(alpha=.09f),shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth().padding(top=10.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Info,null,tint=Lumi,modifier=Modifier.size(20.dp));Spacer(Modifier.width(9.dp));Text("FOREIGN NET FLOW",color=Lumi,fontSize=11.sp,fontWeight=FontWeight.Bold)}
            Row(Modifier.fillMaxWidth().padding(top=10.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                ForeignFlowMetric("1D",foreign1d,Modifier.weight(1f))
                ForeignFlowMetric("10D KUMULATIF",foreign10d,Modifier.weight(1f))
            }
            Text("1D mengikuti Net F pada tanggal referensi. 10D adalah jumlah sepuluh sesi dan dapat berbeda dari tampilan 1M Stockbit.",color=Muted,fontSize=9.sp,modifier=Modifier.padding(top=7.dp))
        }
    }
    broker.flowInterpretation?.let{
        Surface(color=Surface2,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth().padding(top=10.dp)) {
            Column(Modifier.padding(13.dp)){Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Info,null,tint=Lumi,modifier=Modifier.size(18.dp));Spacer(Modifier.width(7.dp));Text("POV BANDAR / FLOW",color=Lumi,fontSize=11.sp,fontWeight=FontWeight.Bold)};Text(it,fontSize=12.sp,modifier=Modifier.padding(top=7.dp))}
        }
    }
    Text("Interpretasi pola broker; bukan bukti identitas atau niat satu pihak.",color=Warn,fontSize=10.sp,modifier=Modifier.padding(top=7.dp))
}

@Composable private fun ScoreTile(label:String,value:Double?,modifier:Modifier=Modifier) {
    Surface(color=Surface2,shape=RoundedCornerShape(13.dp),modifier=modifier) {
        Column(Modifier.padding(vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(value?.toInt()?.toString()?:"—",color=if(value==null)Muted else Lumi,fontWeight=FontWeight.Black,fontSize=18.sp);Text(label,color=Muted,fontSize=8.sp,fontWeight=FontWeight.Bold,maxLines=1)}
    }
}

@Composable private fun AnalysisMetric(label:String,value:String,modifier:Modifier=Modifier,valueColor:Color=Color.White) {
    Surface(color=Surface2,shape=RoundedCornerShape(12.dp),modifier=modifier) { Column(Modifier.padding(10.dp)){Text(label,color=Muted,fontSize=8.sp,fontWeight=FontWeight.Bold);Text(value,color=valueColor,fontWeight=FontWeight.Bold,fontSize=13.sp)} }
}

@Composable private fun ForeignFlowMetric(label:String,value:Double?,modifier:Modifier=Modifier) {
    val valueColor=value?.let{if(it>=0)Good else Bad}?:Muted
    Surface(color=Surface2,shape=RoundedCornerShape(11.dp),modifier=modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(label,color=Muted,fontSize=8.sp,fontWeight=FontWeight.Bold)
            Text(value?.let(::compactIdr)?:"N/A",color=valueColor,fontSize=14.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(top=2.dp))
        }
    }
}

@Composable private fun TradePlanTile(label:String,value:String,color:Color,modifier:Modifier=Modifier.fillMaxWidth()) {
    Surface(color=color.copy(alpha=.10f),shape=RoundedCornerShape(13.dp),modifier=modifier) { Column(Modifier.padding(12.dp)){Text(label,color=color,fontSize=9.sp,fontWeight=FontWeight.Bold);Text(value,fontWeight=FontWeight.Black,fontSize=14.sp,modifier=Modifier.padding(top=2.dp))} }
}

@Composable private fun BrokerPeriodTile(label:String,value:Double?,modifier:Modifier=Modifier) {
    val color=value?.let{if(it>=0)Good else Bad}?:Muted
    Surface(color=color.copy(alpha=.09f),shape=RoundedCornerShape(11.dp),modifier=modifier) { Column(Modifier.padding(horizontal=6.dp,vertical=9.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(label,color=Muted,fontSize=8.sp,fontWeight=FontWeight.Bold);Text(value?.let(::compactIdr)?:"N/A",color=color,fontSize=10.sp,fontWeight=FontWeight.Bold,maxLines=1)} }
}

@Composable private fun BrokerSectionTitle(label:String,icon:androidx.compose.ui.graphics.vector.ImageVector,color:Color) {
    Row(Modifier.padding(top=14.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically){Icon(icon,null,tint=color,modifier=Modifier.size(16.dp));Spacer(Modifier.width(6.dp));Text(label,color=color,fontSize=10.sp,fontWeight=FontWeight.Bold)}
}

@Composable private fun BrokerItemRow(rank:Int,item:BrokerFlowItem,color:Color) {
    Surface(color=color.copy(alpha=.075f),shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth().padding(vertical=3.dp)) {
        Row(Modifier.padding(horizontal=11.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("#$rank",color=Muted,fontSize=9.sp,modifier=Modifier.width(25.dp))
            Surface(color=color.copy(alpha=.16f),shape=RoundedCornerShape(8.dp)){Text(item.code,color=color,fontWeight=FontWeight.Black,fontSize=12.sp,modifier=Modifier.padding(horizontal=8.dp,vertical=5.dp))}
            Column(Modifier.weight(1f).padding(horizontal=9.dp)){Text(item.investorClass?:"Kategori tidak tersedia",color=Muted,fontSize=10.sp);item.averagePrice?.let{Text("Avg Rp${it.toInt().formatIdr()}",fontSize=10.sp)}}
            Text(compactIdr(item.netValue),color=color,fontWeight=FontWeight.Black,fontSize=12.sp)
        }
    }
}

@Composable private fun DetailRow(label:String,value:String,valueColor:Color=Color.White){Row(Modifier.fillMaxWidth().padding(vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){Text(label,color=Muted,modifier=Modifier.weight(.42f));Text(value,color=valueColor,fontWeight=FontWeight.Medium,modifier=Modifier.weight(.58f))}}
@Composable private fun StatusBadge(status:SignalStatus){val color=when(status){SignalStatus.TAKE_PROFIT->Good;SignalStatus.STOP_LOSS,SignalStatus.AMBIGUOUS->Bad;else->Warn};Surface(color=color.copy(alpha=.14f),shape=RoundedCornerShape(20.dp)){Text(status.name.replace('_',' '),color=color,fontWeight=FontWeight.Bold,fontSize=10.sp,modifier=Modifier.padding(horizontal=9.dp,vertical=5.dp))}}
@Composable private fun NumberRow(label:String,value:String,onChange:(String)->Unit){Row(verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));OutlinedTextField(value,onChange,modifier=Modifier.width(100.dp),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),suffix={Text("%")})}}
@Composable private fun LumiField(value:String,onChange:(String)->Unit,label:String,number:Boolean=false)=OutlinedTextField(value,onChange,label={Text(label)},modifier=Modifier.fillMaxWidth(),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=if(number)KeyboardType.Decimal else KeyboardType.Text))
@Composable private fun SecretField(value:String,onChange:(String)->Unit,label:String)=OutlinedTextField(value,onChange,label={Text(label)},modifier=Modifier.fillMaxWidth(),singleLine=true,visualTransformation=PasswordVisualTransformation(),leadingIcon={Icon(Icons.Default.Lock,null)})
@Composable private fun SnackbarDialog(text:String,onDismiss:()->Unit)=AlertDialog(onDismissRequest=onDismiss,title={Text("Lumi Signal")},text={Text(text)},confirmButton={TextButton(onClick=onDismiss){Text("OK")}})

@Composable private fun PriceChart(candles:List<Candle>,signal:SignalEntity) {
    if(candles.size<2){Text("Chart data unavailable.",color=Muted);return}
    val minP=min(candles.minOf{it.low},signal.stopLoss.toDouble())
    val maxP=max(candles.maxOf{it.high},signal.takeProfit.toDouble())
    val span=(maxP-minP).coerceAtLeast(1.0)
    Canvas(Modifier.fillMaxWidth().height(220.dp)) {
        fun chartY(price:Double):Float = size.height-((price-minP)/span*size.height).toFloat()
        fun chartX(index:Int):Float = index*size.width/(candles.size-1)
        drawLine(Good.copy(alpha=.65f),Offset(0f,chartY(signal.takeProfit.toDouble())),Offset(size.width,chartY(signal.takeProfit.toDouble())),strokeWidth=2f)
        drawLine(Bad.copy(alpha=.65f),Offset(0f,chartY(signal.stopLoss.toDouble())),Offset(size.width,chartY(signal.stopLoss.toDouble())),strokeWidth=2f)
        drawLine(Warn.copy(alpha=.65f),Offset(0f,chartY((signal.entryLow+signal.entryHigh)/2.0)),Offset(size.width,chartY((signal.entryLow+signal.entryHigh)/2.0)),strokeWidth=2f)
        val path=Path()
        candles.forEachIndexed{i,c->if(i==0)path.moveTo(chartX(i),chartY(c.close))else path.lineTo(chartX(i),chartY(c.close))}
        drawPath(path,Lumi,style=Stroke(width=4f,cap=StrokeCap.Round))
    }
}

@Composable private fun CandidateCandlestickChart(candles:List<Candle>,candidate:Candidate) {
    val snapshot=candidate.technical
    val history=candles.filter{it.open>0&&it.high>0&&it.low>0&&it.close>0}.takeLast(90)
    if(history.size<21){Text("Chart data unavailable.",color=Muted);return}
    val ema20=emaSeries(history.map{it.close},20)
    val ema50=emaSeries(history.map{it.close},50)
    val rsi14=rsiSeries(history.map{it.close},14)
    val start=(history.size-60).coerceAtLeast(0)
    val valid=history.drop(start)
    val visibleEma20=ema20.drop(start)
    val visibleEma50=ema50.drop(start)
    val visibleRsi=rsi14.drop(start)
    val bands=history.indices.map { index ->
        val values=history.take(index+1).takeLast(20).map{it.close};val mean=values.average();val sd=kotlin.math.sqrt(values.sumOf{(it-mean)*(it-mean)}/values.size.coerceAtLeast(1));mean+2*sd to mean-2*sd
    }.drop(start)
    val rawMin=min(valid.minOf{it.low},min(visibleEma20.minOrNull()?:Double.MAX_VALUE,visibleEma50.minOrNull()?:Double.MAX_VALUE))
    val rawMax=max(valid.maxOf{it.high},max(visibleEma20.maxOrNull()?:Double.MIN_VALUE,visibleEma50.maxOrNull()?:Double.MIN_VALUE))
    val padding=((rawMax-rawMin)*.06).coerceAtLeast(rawMax*.005)
    val minP=rawMin-padding
    val maxP=rawMax+padding
    val span=(maxP-minP).coerceAtLeast(1.0)
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)){Text("${valid.size} sesi sampai reference closing",color=Muted,fontSize=10.sp);Text("Close Rp${valid.last().close.toInt().formatIdr()}",fontWeight=FontWeight.Bold)}
        Surface(color=Lumi.copy(alpha=.12f),shape=RoundedCornerShape(20.dp)){Text("RSI ${"%.1f".format(snapshot.rsi14)}",color=Lumi,fontSize=10.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(horizontal=10.dp,vertical=6.dp))}
    }
    Canvas(Modifier.fillMaxWidth().height(250.dp).padding(top=12.dp)) {
        fun y(price:Double):Float=size.height-((price-minP)/span*size.height).toFloat()
        val step=size.width/valid.size
        val bodyWidth=(step*.58f).coerceAtLeast(2f)
        repeat(5){index->val lineY=size.height*index/4f;drawLine(Surface2,Offset(0f,lineY),Offset(size.width,lineY),strokeWidth=1.5f)}
        valid.forEachIndexed{index,candle->
            val x=(index+.5f)*step
            val color=if(candle.close>=candle.open)Good else Bad
            val openY=y(candle.open);val closeY=y(candle.close)
            drawLine(color,Offset(x,y(candle.high)),Offset(x,y(candle.low)),strokeWidth=2f)
            drawRect(color,Offset(x-bodyWidth/2,min(openY,closeY)),Size(bodyWidth,abs(closeY-openY).coerceAtLeast(2.5f)))
        }
        fun drawIndicator(values:List<Double>,color:Color) {
            val path=Path()
            values.forEachIndexed { index,value ->
                val x=(index+.5f)*step
                if(index==0)path.moveTo(x,y(value))else path.lineTo(x,y(value))
            }
            drawPath(path,color,style=Stroke(width=3f,cap=StrokeCap.Round))
        }
        drawLine(Good.copy(alpha=.55f),Offset(0f,y(snapshot.resistance)),Offset(size.width,y(snapshot.resistance)),strokeWidth=2f)
        drawLine(Warn.copy(alpha=.55f),Offset(0f,y(snapshot.support)),Offset(size.width,y(snapshot.support)),strokeWidth=2f)
        when(candidate.strategy){
            StrategyType.VOLATILITY_COMPRESSION->{drawIndicator(bands.map{it.first},Lumi);drawIndicator(bands.map{it.second},Lumi)}
            StrategyType.REACCUMULATION_AFTER_FIRST_MARKUP->{drawIndicator(visibleEma20,Lumi);drawIndicator(visibleEma50,Warn)}
            else->Unit
        }
    }
    Row(Modifier.fillMaxWidth().padding(top=5.dp),horizontalArrangement=Arrangement.spacedBy(13.dp)) {Text("● Bullish",color=Good,fontSize=9.sp);Text("● Bearish",color=Bad,fontSize=9.sp);Text("━ Resistance",color=Good,fontSize=9.sp);Text("━ Support",color=Warn,fontSize=9.sp)}
    if(candidate.strategy==StrategyType.REACCUMULATION_AFTER_FIRST_MARKUP){
        Text("RSI14",color=Muted,fontSize=9.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=14.dp))
        Canvas(Modifier.fillMaxWidth().height(86.dp).padding(top=5.dp)){
            fun rsiY(value:Double)=size.height-(value.coerceIn(0.0,100.0)/100.0*size.height).toFloat()
            drawLine(Bad.copy(alpha=.45f),Offset(0f,rsiY(70.0)),Offset(size.width,rsiY(70.0)),strokeWidth=1.5f)
            drawLine(Good.copy(alpha=.45f),Offset(0f,rsiY(30.0)),Offset(size.width,rsiY(30.0)),strokeWidth=1.5f)
            val step=size.width/(visibleRsi.size-1).coerceAtLeast(1)
            val path=Path()
            visibleRsi.forEachIndexed{index,value->if(index==0)path.moveTo(0f,rsiY(value))else path.lineTo(index*step,rsiY(value))}
            drawPath(path,Blue,style=Stroke(width=3f,cap=StrokeCap.Round))
        }
    }else{
        Text("VOLUME • sesuai fokus strategi",color=Muted,fontSize=9.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=14.dp))
        val maxVolume=valid.maxOf{it.volume}.coerceAtLeast(1)
        Canvas(Modifier.fillMaxWidth().height(86.dp).padding(top=5.dp)){
            val step=size.width/valid.size
            valid.forEachIndexed{index,candle->
                val height=size.height*(candle.volume.toFloat()/maxVolume)
                drawRect(if(candle.close>=candle.open)Good.copy(alpha=.65f)else Bad.copy(alpha=.65f),Offset(index*step,size.height-height),Size((step*.7f).coerceAtLeast(1f),height))
            }
        }
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(candidate.indicators.joinToString(" • "){"${it.label} ${it.value}"}.take(90),color=Blue,fontSize=9.sp);Text("ATR14 ${"%.1f".format(snapshot.atr14)}",color=Muted,fontSize=9.sp)}
}

private fun emaSeries(values:List<Double>,period:Int):List<Double> {
    if(values.isEmpty())return emptyList()
    val alpha=2.0/(period+1.0)
    var current=values.first()
    return values.mapIndexed { index,value ->
        if(index>0)current=value*alpha+current*(1-alpha)
        current
    }
}

private fun rsiSeries(values:List<Double>,period:Int):List<Double> = values.indices.map { index ->
    if(index==0)50.0 else {
        val changes=values.take(index+1).zipWithNext { a,b->b-a }.takeLast(period)
        val gain=changes.filter{it>0}.sum()/changes.size.coerceAtLeast(1)
        val loss=-changes.filter{it<0}.sum()/changes.size.coerceAtLeast(1)
        if(loss==0.0)100.0 else 100.0-100.0/(1.0+gain/loss)
    }
}

private fun setupLabel(v:String)=when(v){"EARLY_ACCUMULATION"->"🟢 EARLY ACCUMULATION";"BREAKOUT"->"🔥 BREAKOUT";"MOMENTUM"->"🚀 MOMENTUM";"DISTRIBUTION_RISK"->"⚠️ DISTRIBUTION RISK";else->"🟡 WATCHLIST"}
private fun setupColor(v:String)=if(v=="DISTRIBUTION_RISK")Bad else if(v=="WATCHLIST")Warn else Good
private val dateFmt=DateTimeFormatter.ofPattern("dd MMM yyyy").withZone(ZoneId.of("Asia/Jakarta"));private val timeFmt=DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Jakarta"))
private fun date(msOrSec:Long)=dateFmt.format(Instant.ofEpochMilli(if(msOrSec<10_000_000_000L)msOrSec*1000 else msOrSec));private fun time(ms:Long)=timeFmt.format(Instant.ofEpochMilli(ms))
private fun signedPct(value:Double)="${if(value>=0) "+" else ""}${"%.2f".format(value)}%"
private fun money(value:Double)="Rp${java.text.NumberFormat.getIntegerInstance(java.util.Locale("id","ID")).format(value)}"
private fun fmt(value:Double)=java.text.NumberFormat.getNumberInstance(java.util.Locale("id","ID")).format(value)
internal fun compactVolume(value:Long):String {
    val magnitude=kotlin.math.abs(value.toDouble())
    val divisorAndSuffix=when {
        magnitude>=1_000_000_000 -> 1_000_000_000.0 to "B"
        magnitude>=1_000_000 -> 1_000_000.0 to "M"
        magnitude>=1_000 -> 1_000.0 to "K"
        else -> return value.toString()
    }
    val scaled=magnitude/divisorAndSuffix.first
    val decimals=if(scaled>=100)1 else 2
    var number=String.format(java.util.Locale("id","ID"),"%.${decimals}f",scaled)
    number=number.replace(Regex("([,.]0+)$"),"")
    return (if(value<0)"-" else "")+number+divisorAndSuffix.second
}
private fun compactIdr(value:Double):String{val sign=if(value>=0)"+" else "-";val magnitude=kotlin.math.abs(value);return when{magnitude>=1_000_000_000_000->"$sign${"%.2f".format(magnitude/1_000_000_000_000)}T";magnitude>=1_000_000_000->"$sign${"%.2f".format(magnitude/1_000_000_000)}B";magnitude>=1_000_000->"$sign${"%.2f".format(magnitude/1_000_000)}M";else->"$sign${magnitude.toLong()}"}}
private fun chasingSummary(value:Double):String=when{value<=0.0->"Chasing rendah: return 20D belum >8% dan posisi harga belum >5% di atas EMA20.";value<35->"Chasing rendah–moderat: harga mulai extended, tetapi belum masuk area peringatan.";value<60->"Chasing moderat: pertimbangkan entry bertahap atau pullback.";else->"Chasing tinggi: hindari entry agresif dan tunggu pullback."}
private fun flowItem(item:BrokerFlowItem):String=buildString{append(item.code);item.investorClass?.let{append(" ($it)")};append(" ");append(compactIdr(item.netValue));item.averagePrice?.let{append(" @");append(it.toInt().formatIdr())}}
