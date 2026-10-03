pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Rokid CXR SDK (client-m on the phone, cxr-service-bridge on the glasses)
        maven("https://maven.rokid.com/repository/maven-public/") {
            content { includeGroup("com.rokid.cxr") }
        }
    }
}

rootProject.name = "BayBin"
include(":protocol", ":glasses-app", ":phone-app")
