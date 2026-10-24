pluginManagement {
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "QwertyLauncher"

// Shell: loads modules and hosts the home screen.
include(":app")
// Contracts every module codes against. Keep it free of feature logic.
include(":core")
// Features: each builds into a module zip (gradle/module.gradle.kts) that the launcher
// loads at run time. Bundled ones are listed, in load order, in app/build.gradle.kts.
include(":feature:apps")
include(":feature:favorites")
include(":feature:asciiicons")
include(":feature:clock")
include(":feature:battery")
include(":feature:music")
include(":feature:weather")
include(":feature:contacts")
include(":feature:commands")
include(":feature:ai")
include(":feature:notifications")
include(":feature:system")
include(":feature:network")
include(":feature:alarm")
include(":feature:todo")
include(":feature:calendar")
include(":feature:news")
include(":feature:toggles")
include(":feature:sky")
include(":feature:herald")
include(":feature:sensors")
include(":feature:files")
include(":feature:suggest")
include(":feature:games")
