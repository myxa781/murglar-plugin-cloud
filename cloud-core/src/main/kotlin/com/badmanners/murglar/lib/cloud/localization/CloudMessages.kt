package com.badmanners.murglar.lib.cloud.localization

import com.badmanners.murglar.lib.core.localization.DefaultMessages
import com.badmanners.murglar.lib.core.localization.Messages
import com.badmanners.murglar.lib.core.localization.RussianMessages


interface CloudMessages : Messages {
    val addAccount: String
    val removeAccount: String
    val enterToken: String
    val tokenHint: String
    val noAccounts: String
    val noAudioFiles: String
    val noTags: String
    val rootFolder: String
    val allAudioFiles: String
    val cloudSearch: String
    val yandexDisk: String
    val dropbox: String
    val googleDrive: String
    val accountAdded: String
    val accountRemoved: String
    val favorites: String
}


object CloudDefaultMessages : DefaultMessages(), CloudMessages {
    override val serviceName = "Cloud Music"
    override val playlists = "Accounts"
    override val youAreNotLoggedIn = "Add a cloud account to get started"
    override val addAccount = "Add account"
    override val removeAccount = "Remove account"
    override val enterToken = "Access token"
    override val tokenHint = "Paste OAuth access token"
    override val noAccounts = "No accounts connected"
    override val noAudioFiles = "No audio files found"
    override val noTags = "Tags are not supported"
    override val rootFolder = "Root"
    override val allAudioFiles = "All audio files"
    override val cloudSearch = "Search"
    override val yandexDisk = "Yandex Disk"
    override val dropbox = "Dropbox"
    override val googleDrive = "Google Drive"
    override val accountAdded = "Account added"
    override val accountRemoved = "Account removed"
    override val favorites = "Favorites"
}


object CloudRussianMessages : RussianMessages(), CloudMessages {
    override val serviceName = "Облачная музыка"
    override val playlists = "Аккаунты"
    override val youAreNotLoggedIn = "Добавьте облачный аккаунт"
    override val addAccount = "Добавить аккаунт"
    override val removeAccount = "Удалить аккаунт"
    override val enterToken = "Токен доступа"
    override val tokenHint = "Вставьте OAuth access token"
    override val noAccounts = "Нет подключённых аккаунтов"
    override val noAudioFiles = "Аудиофайлы не найдены"
    override val noTags = "Теги не поддерживаются"
    override val rootFolder = "Корень"
    override val allAudioFiles = "Все аудиофайлы"
    override val cloudSearch = "Поиск"
    override val yandexDisk = "Яндекс Диск"
    override val dropbox = "Dropbox"
    override val googleDrive = "Google Drive"
    override val accountAdded = "Аккаунт добавлен"
    override val accountRemoved = "Аккаунт удалён"
    override val favorites = "Избранное"
}
