allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

val newBuildDir: File = rootProject.layout.buildDirectory.dir("../../build").get().asFile
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: File = newBuildDir.resolve(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
