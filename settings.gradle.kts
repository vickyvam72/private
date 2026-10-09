pluginManagement {
    val localMavenProxy = System.getenv("LUMI_MAVEN_PROXY")?.trimEnd('/')
    repositories {
        if (localMavenProxy != null) {
            maven { url = uri("$localMavenProxy/maven"); isAllowInsecureProtocol = true }
            maven { url = uri("$localMavenProxy/google"); isAllowInsecureProtocol = true }
            maven { url = uri("$localMavenProxy/plugins"); isAllowInsecureProtocol = true }
        } else {
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
}

dependencyResolutionManagement {
    val localMavenProxy = System.getenv("LUMI_MAVEN_PROXY")?.trimEnd('/')
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (localMavenProxy != null) {
            maven { url = uri("$localMavenProxy/google"); isAllowInsecureProtocol = true }
            maven { url = uri("$localMavenProxy/maven"); isAllowInsecureProtocol = true }
        } else {
            google()
            mavenCentral()
        }
    }
}

rootProject.name = "LumiSignalIdxScreener"
include(":app")
