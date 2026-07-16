import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.net.URI

// DSL for declaring which Material Symbol an app's launcher icon is built from:
//
//     launcherIcon {
//         symbol = "calendar_month"
//     }
//
// `common-conventions-app` reads this and generates the app's adaptive-icon
// foreground vector at build time, so no ic_launcher_foreground.xml is committed.
interface LauncherIconExtension {
    /** Material Symbols name, e.g. "calendar_month" (the `materialsymbolsoutlined` style). */
    val symbol: Property<String>
}

// Downloads the outlined Material Symbol at [ref], wraps its path in the standard
// adaptive-icon foreground vector, and writes it into an AGP-managed generated res
// directory. Raw symbol XML is cached under [cacheDir] so `clean` builds and
// offline rebuilds don't re-hit the network.
abstract class GenerateLauncherIconTask : DefaultTask() {
    @get:Input
    abstract val symbol: Property<String>

    @get:Input
    abstract val ref: Property<String>

    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val sym = symbol.orNull
            ?: throw GradleException(
                "No launcher icon symbol set for ${path.substringBeforeLast(':')}. " +
                    "Declare one in build.gradle.kts:\n\n    launcherIcon {\n        symbol = \"calendar_month\"\n    }\n"
            )
        val gitRef = ref.get()
        val raw = fetchRawSymbol(sym, gitRef)
        val pathData = PATH_DATA.find(raw)?.groupValues?.get(1)
            ?: throw GradleException("Could not find android:pathData in Material Symbol '$sym' (ref $gitRef)")

        val drawableDir = outputDir.get().asFile.resolve("drawable").apply { mkdirs() }
        drawableDir.resolve("ic_launcher_foreground.xml").writeText(wrap(pathData))
    }

    private fun fetchRawSymbol(sym: String, gitRef: String): String {
        val cached = cacheDir.get().asFile.resolve(gitRef).resolve("${sym}_24px.xml")
        if (cached.isFile && cached.length() > 0) return cached.readText()

        val url =
            "https://raw.githubusercontent.com/google/material-design-icons/$gitRef" +
                "/symbols/android/$sym/materialsymbolsoutlined/${sym}_24px.xml"
        val text = try {
            URI(url).toURL().openStream().bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            throw GradleException(
                "Failed to download Material Symbol '$sym' from $url. " +
                    "Check the symbol name and network access.",
                e,
            )
        }
        if (!text.contains("pathData")) {
            throw GradleException("Material Symbol '$sym' not found at ref $gitRef (url: $url)")
        }
        cached.parentFile.mkdirs()
        cached.writeText(text)
        return text
    }

    private fun wrap(pathData: String): String =
        """
        |<vector xmlns:android="http://schemas.android.com/apk/res/android"
        |    android:width="108dp"
        |    android:height="108dp"
        |    android:viewportWidth="960"
        |    android:viewportHeight="960"
        |    android:tint="#000000">
        |  <group android:scaleX="0.58"
        |      android:scaleY="0.58"
        |      android:translateX="201.6"
        |      android:translateY="201.6">
        |    <path
        |        android:fillColor="@android:color/white"
        |        android:pathData="$pathData"/>
        |  </group>
        |</vector>
        |
        """.trimMargin()

    private companion object {
        val PATH_DATA = Regex("""android:pathData="([^"]*)"""")
    }
}
