package space.webkombinat.MobileWebCamera.util

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * 表示用テキスト。カメラ層は Context を持たないので文字列リソースの ID だけを保持し、
 * UI 側で現在の言語に解決する（言語を切り替えても状態を作り直さなくてよい）。
 */
sealed interface UiText {
    fun resolve(context: Context): String

    /** 翻訳不要な文字列（OFF / FAST などの技術用語、例外メッセージなど） */
    data class Raw(val value: String) : UiText {
        override fun resolve(context: Context) = value
    }

    /** [args] に UiText を含めた場合は先に解決してから埋め込む */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText {
        override fun resolve(context: Context): String =
            if (args.isEmpty()) context.getString(id)
            else context.getString(id, *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray())
    }
}

fun text(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

fun raw(value: String): UiText = UiText.Raw(value)

@Composable
fun UiText.asString(): String {
    LocalConfiguration.current // 言語変更で再コンポーズさせる
    return resolve(LocalContext.current)
}
