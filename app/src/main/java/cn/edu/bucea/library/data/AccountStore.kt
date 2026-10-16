package cn.edu.bucea.library.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import cn.edu.bucea.library.AccountSummary
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedAccount(val id: String, val username: String, val password: String, val label: String) {
    fun summary() = AccountSummary(id, username, label)
}

/** Credentials and per-account cookies are encrypted using a non-exportable device key. */
class AccountStore(context: Context) {
    private val preferences = context.getSharedPreferences("library_vault", Context.MODE_PRIVATE)
    private val alias = "bucea_library_vault_v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    @Synchronized private fun read(name: String): String? {
        val encoded = preferences.getString(name, null) ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    @Synchronized private fun write(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString(name, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) {
            "无法保存账号，请检查设备存储空间"
        }
    }

    @Synchronized fun accounts(): List<SavedAccount> {
        val data = JSONArray(read("accounts") ?: "[]")
        return (0 until data.length()).map { i -> data.getJSONObject(i).let {
            SavedAccount(it.getString("id"), it.getString("username"), it.getString("password"), it.getString("label"))
        } }
    }

    @Synchronized fun save(username: String, password: String, label: String): SavedAccount {
        require(username.matches(Regex("[A-Za-z0-9]{4,30}"))) { "请输入正确的学号" }
        require(password.isNotBlank()) { "请输入密码" }
        val accounts = accounts().toMutableList()
        val old = accounts.find { it.username == username }
        val account = SavedAccount(old?.id ?: UUID.randomUUID().toString(), username, password, label.ifBlank { "图书馆账号" })
        accounts.removeAll { it.id == account.id }
        accounts.add(account)
        writeAccounts(accounts)
        clearCookies(account.id)
        setSignedOut(account.id, false)
        activeAccountId = account.id
        return account
    }

    private fun writeAccounts(accounts: List<SavedAccount>) = write("accounts", JSONArray().apply {
        accounts.forEach { put(JSONObject().put("id", it.id).put("username", it.username)
            .put("password", it.password).put("label", it.label)) }
    }.toString())

    /** Update only the encrypted label, preserving credentials, cookies and account choices. */
    @Synchronized fun updateLabel(id: String, label: String): SavedAccount {
        val accounts = accounts()
        val index = accounts.indexOfFirst { it.id == id }
        require(index >= 0) { "账号不存在，请刷新账号列表" }
        val updated = accounts[index].copy(label = label.trim())
        writeAccounts(accounts.mapIndexed { position, account -> if (position == index) updated else account })
        return updated
    }

    @Synchronized fun delete(id: String) {
        val remaining = accounts().filterNot { it.id == id }
        writeAccounts(remaining)
        clearCookies(id)
        check(preferences.edit().remove("signed_out_$id").commit())
        if (activeAccountId == id) activeAccountId = remaining.firstOrNull()?.id
    }

    var activeAccountId: String?
        get() = preferences.getString("active", null)
        set(value) { preferences.edit().putString("active", value).apply() }
    var autoLogin: Boolean
        get() = preferences.getBoolean("auto_login", true)
        set(value) { preferences.edit().putBoolean("auto_login", value).apply() }
    var autoSwitch: Boolean
        get() = preferences.getBoolean("auto_switch", false)
        set(value) { preferences.edit().putBoolean("auto_switch", value).apply() }

    fun cookies(id: String): String = read("cookies_$id") ?: "[]"
    fun saveCookies(id: String, json: String) = write("cookies_$id", json)
    fun clearCookies(id: String) { preferences.edit().remove("cookies_$id").commit() }

    fun isSignedOut(id: String): Boolean = preferences.getBoolean("signed_out_$id", false)
    fun setSignedOut(id: String, signedOut: Boolean) {
        check(preferences.edit().putBoolean("signed_out_$id", signedOut).commit()) {
            "无法保存退出状态，请检查设备存储空间"
        }
    }
}
