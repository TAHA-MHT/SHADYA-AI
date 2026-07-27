allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

subprojects {
    afterEvaluate {
        if (project.plugins.hasPlugin("com.android.library") || project.plugins.hasPlugin("com.android.application")) {
            extensions.findByType(com.android.build.gradle.BaseExtension::class.java)?.apply {
                compileSdkVersion(36)
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
                // Certains anciens plugins (ex: vosk_flutter_2) ne déclarent pas
                // de "namespace" (requis par les versions récentes d'AGP), mais
                // ont encore un attribut "package" dans leur AndroidManifest.xml.
                // On lit ce package existant et on l'utilise comme namespace,
                // pour que les deux correspondent exactement.
                if (namespace == null) {
                    val manifestFile = project.file("src/main/AndroidManifest.xml")
                    if (manifestFile.exists()) {
                        val manifestText = manifestFile.readText()
                        val match = Regex("package=\"([^\"]+)\"").find(manifestText)
                        if (match != null) {
                            namespace = match.groupValues[1]
                        }
                    }
                }
            }
        }

        tasks.withType(JavaCompile::class.java).configureEach {
            sourceCompatibility = JavaVersion.VERSION_17.toString()
            targetCompatibility = JavaVersion.VERSION_17.toString()
        }

        plugins.withType(org.jetbrains.kotlin.gradle.plugin.KotlinBasePluginWrapper::class.java) {
            extensions.configure(org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension::class.java) {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                }
            }
        }
    }
}
