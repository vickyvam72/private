buildscript {
    repositories {
        val localMavenProxy = System.getenv("LUMI_MAVEN_PROXY")?.trimEnd('/')
        if (localMavenProxy != null) {
            maven { url = uri("$localMavenProxy/maven"); isAllowInsecureProtocol = true }
            maven { url = uri("$localMavenProxy/google"); isAllowInsecureProtocol = true }
        } else {
            google()
            mavenCentral()
        }
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.0.21")
    }
}
