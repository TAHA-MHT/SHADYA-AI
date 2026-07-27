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
                // de "namespace", requis par les versions récentes d'Android
                // Gradle Plugin. On lui en attribue un automatiquement s'il en
                // manque un, pour éviter que le build échoue.
                if (namespace == null) {
                    namespace = "com.shadyaai.vendor.${project.name.replace("-", "_").replace(".", "_")}"
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
