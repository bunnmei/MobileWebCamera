package space.webkombinat.MobileWebCamera.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RawRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.webkombinat.MobileWebCamera.R
import space.webkombinat.MobileWebCamera.ui.components.Hint
import space.webkombinat.MobileWebCamera.ui.components.SectionTitle

private const val SOURCE_URL = "https://github.com/bunnmei/MobileWebCamera"

/** 実行時に同梱されるライブラリ（releaseRuntimeClasspath）。すべて Apache License 2.0 */
private data class OssLibrary(val name: String, val detail: String, val copyright: String, val url: String)

private val LIBRARIES = listOf(
    OssLibrary(
        "Android Jetpack (AndroidX)",
        "Activity, Core, Lifecycle, SavedState, Hilt, Collection, Window …",
        "The Android Open Source Project",
        "https://developer.android.com/jetpack/androidx",
    ),
    OssLibrary(
        "Jetpack Compose",
        "Runtime, UI, Foundation, Animation, Material 3",
        "The Android Open Source Project",
        "https://developer.android.com/compose",
    ),
    OssLibrary("Dagger / Hilt", "Dependency injection", "The Dagger Authors", "https://dagger.dev/hilt/"),
    OssLibrary(
        "Kotlin Standard Library",
        "kotlin-stdlib",
        "JetBrains s.r.o. and Kotlin Programming Language contributors",
        "https://kotlinlang.org/",
    ),
    OssLibrary(
        "kotlinx.coroutines",
        "kotlinx-coroutines-core / android",
        "JetBrains s.r.o. and contributors",
        "https://github.com/Kotlin/kotlinx.coroutines",
    ),
    OssLibrary(
        "kotlinx.serialization",
        "kotlinx-serialization-core",
        "JetBrains s.r.o. and contributors",
        "https://github.com/Kotlin/kotlinx.serialization",
    ),
    OssLibrary("Guava: ListenableFuture", "listenablefuture", "The Guava Authors", "https://github.com/google/guava"),
    OssLibrary(
        "javax.inject / Jakarta Dependency Injection",
        "JSR-330",
        "The JSR-330 Expert Group / Eclipse Foundation",
        "https://github.com/jakartaee/inject",
    ),
    OssLibrary("JSpecify", "Nullness annotations", "The JSpecify Authors", "https://jspecify.dev/"),
    OssLibrary(
        "JetBrains Java Annotations",
        "org.jetbrains:annotations",
        "JetBrains s.r.o.",
        "https://github.com/JetBrains/java-annotations",
    ),
    OssLibrary("JSR-305 Annotations", "com.google.code.findbugs:jsr305", "FindBugs", "https://github.com/findbugsproject/findbugs"),
)

@Composable
fun AboutTab() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val version = remember { context.versionName() }

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
    Hint(stringResource(R.string.about_version, version))
    TextButton(onClick = { uriHandler.openUri(SOURCE_URL) }) {
        Text(stringResource(R.string.about_source))
    }

    SectionTitle(stringResource(R.string.about_app_license))
    LicenseText("0BSD", R.raw.license_0bsd)

    SectionTitle(stringResource(R.string.about_oss))
    Hint(stringResource(R.string.about_oss_hint))
    LIBRARIES.forEach { lib ->
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(lib.name, style = MaterialTheme.typography.titleSmall)
            Hint(lib.detail)
            Hint("© ${lib.copyright}")
            Hint("Apache License 2.0")
            Text(
                lib.url,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp).clickable { uriHandler.openUri(lib.url) },
            )
        }
        HorizontalDivider()
    }
    Spacer(Modifier.height(8.dp))
    LicenseText("Apache License 2.0", R.raw.license_apache_2_0)
}

/** ライセンス名と「全文を表示」ボタン。全文は開いたときだけ読み込む */
@Composable
private fun LicenseText(name: String, @RawRes res: Int) {
    val resources = LocalResources.current
    var expanded by rememberSaveable(res) { mutableStateOf(false) }
    Text(name, style = MaterialTheme.typography.titleSmall)
    TextButton(onClick = { expanded = !expanded }) {
        Text(stringResource(if (expanded) R.string.about_hide_license else R.string.about_show_license))
    }
    if (expanded) {
        val body = remember(res) { resources.openRawResource(res).bufferedReader().use { it.readText() } }
        SelectionContainer {
            Text(body, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp)
        }
    }
}

private fun Context.versionName(): String {
    val info = if (Build.VERSION.SDK_INT >= 33) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0)
    }
    return info.versionName ?: "-"
}
