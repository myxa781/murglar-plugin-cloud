package com.badmanners.murglar.lib.cloud.login

import com.badmanners.murglar.lib.core.login.CredentialsLoginVariant
import com.badmanners.murglar.lib.core.login.CredentialsLoginVariant.Credential
import com.badmanners.murglar.lib.core.login.CredentialLoginStep
import com.badmanners.murglar.lib.core.login.LoginResolver
import com.badmanners.murglar.lib.core.login.SuccessfulLogin
import com.badmanners.murglar.lib.core.login.WebLoginVariant
import com.badmanners.murglar.lib.core.preference.PreferenceMiddleware
import com.badmanners.murglar.lib.core.webview.WebViewProvider
import com.badmanners.murglar.lib.cloud.CloudMurglar
import com.badmanners.murglar.lib.cloud.localization.CloudMessages


/**
 * Логин: одна форма с тремя полями — по одному на каждый провайдер.
 * Пользователь заполняет нужные, остальные оставляет пустыми.
 * Все аккаунты добавляются за один вход.
 *
 * Чтобы добавить аккаунт позже: Logout → форма логина появится снова
 * (существующие токены сохранены в loginInfo для копирования).
 */
class CloudLoginResolver(
    private val preferences: PreferenceMiddleware,
    private val murglar: CloudMurglar,
    private val messages: CloudMessages
) : LoginResolver {

    companion object {
        private const val CRED_YANDEX = "yandex_token"
        private const val CRED_DROPBOX = "dropbox_token"
        private const val CRED_GDRIVE = "gdrive_token"
    }

    override val isLogged: Boolean get() = murglar.getAccounts().isNotEmpty()

    override val loginInfo: String
        get() {
            val accounts = murglar.getAccounts()
            return if (accounts.isEmpty()) messages.youAreNotLoggedIn
            else accounts.joinToString("\n") { "${it.provider}: ${it.displayName}" }
        }

    override val webLoginVariants: List<WebLoginVariant> get() = emptyList()

    override val credentialsLoginVariants: List<CredentialsLoginVariant>
        get() {
            val yandexProvider = murglar.findProvider("yandex")
            val dropboxProvider = murglar.findProvider("dropbox")
            val gdriveProvider = murglar.findProvider("gdrive")

            return listOf(
                CredentialsLoginVariant(
                    id = "add_clouds",
                    label = { messages.addAccount },
                    credentials = listOfNotNull(
                        yandexProvider?.let {
                            Credential(CRED_YANDEX, { "${messages.yandexDisk} token (${it.oauthUrl})" })
                        },
                        dropboxProvider?.let {
                            Credential(CRED_DROPBOX, { "${messages.dropbox} token (${it.oauthUrl})" })
                        },
                        gdriveProvider?.let {
                            Credential(CRED_GDRIVE, { "${messages.googleDrive} token (${it.oauthUrl})" })
                        }
                    )
                )
            )
        }

    override suspend fun credentialsLogin(loginVariantId: String, args: Map<String, String>): CredentialLoginStep {
        var added = 0
        val errors = mutableListOf<String>()

        // Yandex
        args[CRED_YANDEX]?.trim()?.takeIf { it.isNotEmpty() }?.let { token ->
            try {
                val provider = murglar.findProvider("yandex")!!
                val name = runCatching { provider.getAccountDisplayName(token) }.getOrDefault("Yandex Disk")
                murglar.addAccount("yandex", name, token)
                added++
            } catch (e: Exception) {
                errors += "Yandex: ${e.message}"
            }
        }

        // Dropbox — обмениваем code на refresh_token
        args[CRED_DROPBOX]?.trim()?.takeIf { it.isNotEmpty() }?.let { token ->
            try {
                val provider = murglar.findProvider("dropbox")!! as com.badmanners.murglar.lib.cloud.api.DropboxApi
                val name = provider.getAccountDisplayName(token)
                val finalToken = provider.resolvedRefreshComposite
                    ?: error("Код обменян, но refresh token не получен")
                murglar.addAccount("dropbox", name, finalToken)
                added++
            } catch (e: Exception) {
                errors += "Dropbox: ${e.message}"
            }
        }

        // Google Drive
        args[CRED_GDRIVE]?.trim()?.takeIf { it.isNotEmpty() }?.let { token ->
            try {
                val provider = murglar.findProvider("gdrive")!!
                val name = runCatching { provider.getAccountDisplayName(token) }.getOrDefault("Google Drive")
                murglar.addAccount("gdrive", name, token)
                added++
            } catch (e: Exception) {
                errors += "Google Drive: ${e.message}"
            }
        }

        if (errors.isNotEmpty()) {
            murglar.notify("⚠ Ошибки: ${errors.joinToString("; ")}")
        }
        require(added > 0) { errors.joinToString("\n").ifEmpty { "Введите хотя бы один токен" } }

        murglar.notify("✓ ${messages.accountAdded}: $added")
        return SuccessfulLogin
    }

    override suspend fun webLogin(loginVariantId: String, webViewProvider: WebViewProvider): Boolean = false

    override fun logout() {
        murglar.clearAccounts()
    }
}
