package com.example.waevirmenasistan

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.example.waevirmenasistan.accessibility.WhatsAppTranslatorAccessibilityService
import com.example.waevirmenasistan.data.AiProvider
import com.example.waevirmenasistan.data.AppSettings
import com.example.waevirmenasistan.data.ReplyTone
import com.example.waevirmenasistan.data.SettingsRepository
import com.example.waevirmenasistan.engine.LlmApiEngine
import com.example.waevirmenasistan.engine.WebsiteKnowledgeFetcher
import com.example.waevirmenasistan.update.GitHubUpdateChecker
import com.example.waevirmenasistan.update.RemoteReleaseInfo
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var repo: SettingsRepository
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var txtHeaderSubtitle: TextView
    private lateinit var txtHeaderModeBadge: TextView
    private lateinit var txtServiceStatusTitle: TextView
    private lateinit var txtServiceStatusDesc: TextView
    private lateinit var btnEnableAccess: Button
    private lateinit var btnOpenWhatsApp: Button

    private lateinit var spnProvider: Spinner
    private lateinit var customUrlContainer: LinearLayout
    private lateinit var edtCustomBaseUrl: EditText
    private lateinit var spnModel: Spinner
    private lateinit var edtCustomModel: EditText
    private lateinit var edtApiKey: EditText
    private lateinit var btnTestApi: Button

    private lateinit var edtBusinessName: EditText
    private lateinit var edtBusinessContext: EditText
    private lateinit var edtWebsiteUrl: EditText
    private lateinit var txtWebsiteStatus: TextView
    private lateinit var spnTone: Spinner
    private lateinit var swInstantClick: Switch

    private lateinit var txtFormStatus: TextView
    private lateinit var btnSaveSettings: Button
    private lateinit var txtVersionFooter: TextView
    private lateinit var txtOtaStatus: TextView
    private lateinit var btnOtaAction: Button

    private var currentModelSelection: String = ""
    private var cachedWebsiteUrl: String = ""
    private var cachedWebsiteKnowledge: String = ""
    private var cachedWebsiteUpdatedAt: Long = 0L
    private var currentVersionName: String = "2.2"
    private var currentVersionCode: Int = 13
    private var pendingRelease: RemoteReleaseInfo? = null
    private var waitingInstallPermission: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.parseColor("#1A1A1A")
        }
        setContentView(R.layout.activity_main)

        repo = SettingsRepository(this)
        bindViews()
        applyVersionLabels()
        populateSpinners()
        loadIntoForm(repo.load())
        setupActions()
        checkRemoteUpdate(manual = false)
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityStatus()
        if (waitingInstallPermission && GitHubUpdateChecker.canInstallPackages(this)) {
            waitingInstallPermission = false
            pendingRelease?.let { downloadAndInstallUpdate(it) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun bindViews() {
        txtHeaderSubtitle = findViewById(R.id.txtHeaderSubtitle)
        txtHeaderModeBadge = findViewById(R.id.txtHeaderModeBadge)
        txtServiceStatusTitle = findViewById(R.id.txtServiceStatusTitle)
        txtServiceStatusDesc = findViewById(R.id.txtServiceStatusDesc)
        btnEnableAccess = findViewById(R.id.btnEnableAccess)
        btnOpenWhatsApp = findViewById(R.id.btnOpenWhatsApp)

        spnProvider = findViewById(R.id.spnProvider)
        customUrlContainer = findViewById(R.id.customUrlContainer)
        edtCustomBaseUrl = findViewById(R.id.edtCustomBaseUrl)
        spnModel = findViewById(R.id.spnModel)
        edtCustomModel = findViewById(R.id.edtCustomModel)
        edtApiKey = findViewById(R.id.edtApiKey)
        btnTestApi = findViewById(R.id.btnTestApi)

        edtBusinessName = findViewById(R.id.edtBusinessName)
        edtBusinessContext = findViewById(R.id.edtBusinessContext)
        edtWebsiteUrl = findViewById(R.id.edtWebsiteUrl)
        txtWebsiteStatus = findViewById(R.id.txtWebsiteStatus)
        spnTone = findViewById(R.id.spnTone)
        swInstantClick = findViewById(R.id.swInstantClick)

        txtFormStatus = findViewById(R.id.txtFormStatus)
        btnSaveSettings = findViewById(R.id.btnSaveSettings)
        txtVersionFooter = findViewById(R.id.txtVersionFooter)
        txtOtaStatus = findViewById(R.id.txtOtaStatus)
        btnOtaAction = findViewById(R.id.btnOtaAction)
    }

    private fun applyVersionLabels() {
        runCatching {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val vName = pInfo.versionName ?: "2.2"
            val vCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            currentVersionName = vName
            currentVersionCode = vCode.toInt()
            txtHeaderSubtitle.text = "Mobil WhatsApp Asistanı • v$vName"
            txtVersionFooter.text = "WA Çevirmen Asistan • Sürüm $vName ($vCode)"
        }
    }

    private fun populateSpinners() {
        spnProvider.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            AiProvider.entries.map { it.label }
        )
        spnTone.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ReplyTone.entries.map { it.label }
        )
    }

    private fun loadIntoForm(s: AppSettings) {
        currentModelSelection = s.model
        cachedWebsiteUrl = s.websiteUrl.trim()
        cachedWebsiteKnowledge = s.websiteKnowledge
        cachedWebsiteUpdatedAt = s.websiteUpdatedAt

        spnProvider.setSelection(AiProvider.entries.indexOf(s.provider).coerceAtLeast(0))
        edtCustomBaseUrl.setText(s.customBaseUrl)
        edtApiKey.setText(s.apiKey)
        edtBusinessName.setText(s.businessName)
        edtBusinessContext.setText(s.businessContext)
        edtWebsiteUrl.setText(s.websiteUrl)
        spnTone.setSelection(ReplyTone.entries.indexOf(s.tone).coerceAtLeast(0))
        swInstantClick.isChecked = s.instantClickTranslate

        if (s.websiteKnowledge.isNotBlank()) {
            txtWebsiteStatus.visibility = View.VISIBLE
            txtWebsiteStatus.setTextColor(Color.parseColor("#69416F"))
            txtWebsiteStatus.text = "Site bilgileri kalıcı hafızada hazır (${s.websiteKnowledge.length} karakter)."
        }

        syncProviderModels(s.provider, s.model)
        txtHeaderModeBadge.text = s.activeBadgeLabel()
        refreshAccessibilityStatus()
    }

    private fun syncProviderModels(provider: AiProvider, preferredModel: String?) {
        customUrlContainer.visibility = if (provider == AiProvider.CUSTOM) View.VISIBLE else View.GONE
        if (provider.models.isEmpty()) {
            spnModel.visibility = View.GONE
            edtCustomModel.visibility = View.VISIBLE
            edtCustomModel.setText(preferredModel?.ifBlank { provider.defaultModel } ?: provider.defaultModel)
        } else {
            spnModel.visibility = View.VISIBLE
            edtCustomModel.visibility = View.GONE
            spnModel.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                provider.models.map { it.second }
            )
            val idx = provider.models.indexOfFirst { it.first == preferredModel }
            spnModel.setSelection(if (idx >= 0) idx else 0)
        }
    }

    private fun collectForm(): AppSettings {
        val provider = AiProvider.entries[spnProvider.selectedItemPosition.coerceIn(0, AiProvider.entries.lastIndex)]
        val tone = ReplyTone.entries[spnTone.selectedItemPosition.coerceIn(0, ReplyTone.entries.lastIndex)]

        val model = if (provider.models.isEmpty()) {
            edtCustomModel.text.toString().trim().ifEmpty { provider.defaultModel }
        } else {
            val idx = spnModel.selectedItemPosition.coerceIn(0, provider.models.lastIndex)
            provider.models[idx].first
        }

        val url = edtWebsiteUrl.text.toString().trim()
        val sameUrl = url == cachedWebsiteUrl
        val knowledge = if (sameUrl) cachedWebsiteKnowledge else ""
        val updatedAt = if (sameUrl) cachedWebsiteUpdatedAt else 0L

        return AppSettings(
            provider = provider,
            apiKey = edtApiKey.text.toString().trim(),
            model = model,
            customBaseUrl = edtCustomBaseUrl.text.toString().trim(),
            tone = tone,
            businessName = edtBusinessName.text.toString().trim(),
            businessContext = edtBusinessContext.text.toString().trim(),
            websiteUrl = url,
            websiteKnowledge = knowledge,
            websiteUpdatedAt = updatedAt,
            instantClickTranslate = swInstantClick.isChecked
        )
    }

    private fun setupActions() {
        edtApiKey.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                txtHeaderModeBadge.text = collectForm().activeBadgeLabel()
            }
        })

        spnProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            private var initialized = false
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val prov = AiProvider.entries[position]
                if (!initialized) {
                    initialized = true
                    syncProviderModels(prov, currentModelSelection)
                } else {
                    syncProviderModels(prov, prov.defaultModel)
                }
                txtHeaderModeBadge.text = collectForm().activeBadgeLabel()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnEnableAccess.setOnClickListener {
            WhatsAppTranslatorAccessibilityService.openAccessibilitySettings(this)
        }

        btnOpenWhatsApp.setOnClickListener {
            val launch = packageManager.getLaunchIntentForPackage("com.whatsapp")
                ?: packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            } else {
                showStatus("Cihazda WhatsApp veya WhatsApp Business bulunamadı.", isError = true)
            }
        }

        btnOtaAction.setOnClickListener {
            val ready = pendingRelease
            if (ready != null) {
                if (!GitHubUpdateChecker.canInstallPackages(this)) {
                    waitingInstallPermission = true
                    txtOtaStatus.text = "Kurulum için 'Bilinmeyen uygulamalara izin ver' açılmalı."
                    GitHubUpdateChecker.openInstallUnknownAppsSettings(this)
                } else {
                    downloadAndInstallUpdate(ready)
                }
            } else {
                checkRemoteUpdate(manual = true)
            }
        }

        btnSaveSettings.setOnClickListener {
            val s = collectForm()
            if (!s.hasApiKey()) {
                showStatus("Lütfen geçerli bir API anahtarı girin.", isError = true)
                return@setOnClickListener
            }

            if (s.websiteUrl.isNotBlank()) {
                syncWebsiteAndSave(s)
            } else {
                cachedWebsiteUrl = ""
                cachedWebsiteKnowledge = ""
                cachedWebsiteUpdatedAt = 0L
                txtWebsiteStatus.visibility = View.GONE
                repo.save(s)
                txtHeaderModeBadge.text = s.activeBadgeLabel()
                showStatus("Ayarlar kaydedildi. WhatsApp'ta anında aktif olacaktır.", isError = false)
            }
        }

        btnTestApi.setOnClickListener {
            val s = collectForm()
            if (!s.hasApiKey()) {
                showStatus("Test etmek için önce geçerli bir API anahtarı girin.", isError = true)
                return@setOnClickListener
            }
            btnTestApi.isEnabled = false
            btnTestApi.text = "Test..."
            executor.execute {
                val res = runCatching {
                    LlmApiEngine().runTurn(
                        settings = s,
                        messageText = "Hello, are you available tomorrow afternoon?",
                        customerName = "Test Müşteri",
                        chatContext = emptyList()
                    )
                }
                mainHandler.post {
                    btnTestApi.isEnabled = true
                    btnTestApi.text = "Test Et"
                    res.onSuccess { out ->
                        showStatus("API bağlantısı başarılı (${out.engineUsed}). Örnek yanıt: \"${out.replyInCustomerLanguage}\"", isError = false)
                    }.onFailure { err ->
                        showStatus("Bağlantı hatası: ${err.message}", isError = true)
                    }
                }
            }
        }
    }

    private fun syncWebsiteAndSave(settings: AppSettings) {
        btnSaveSettings.isEnabled = false
        btnSaveSettings.text = "Site Bilgileri Kaydediliyor..."
        txtWebsiteStatus.visibility = View.VISIBLE
        txtWebsiteStatus.setTextColor(Color.parseColor("#69416F"))
        txtWebsiteStatus.text = "Web sitesi içeriği kalıcı hafızaya alınıyor..."

        executor.execute {
            val res = runCatching { WebsiteKnowledgeFetcher.fetchAndExtract(settings.websiteUrl) }
            mainHandler.post {
                btnSaveSettings.isEnabled = true
                btnSaveSettings.text = "Ayarları Kaydet"
                res.onSuccess { extracted ->
                    val now = System.currentTimeMillis()
                    cachedWebsiteUrl = settings.websiteUrl
                    cachedWebsiteKnowledge = extracted
                    cachedWebsiteUpdatedAt = now

                    val updated = settings.copy(
                        websiteKnowledge = extracted,
                        websiteUpdatedAt = now
                    )
                    repo.save(updated)
                    txtHeaderModeBadge.text = updated.activeBadgeLabel()
                    txtWebsiteStatus.setTextColor(Color.parseColor("#69416F"))
                    txtWebsiteStatus.text = "Site bilgileri kalıcı hafızaya kaydedildi (${extracted.length} karakter)."
                    showStatus("Ayarlar ve web sitesi bilgileri kalıcı olarak kaydedildi.", isError = false)
                }.onFailure { err ->
                    repo.save(settings)
                    txtHeaderModeBadge.text = settings.activeBadgeLabel()
                    txtWebsiteStatus.setTextColor(Color.parseColor("#B42318"))
                    txtWebsiteStatus.text = "Web sitesi şu an alınamadı (ilk WhatsApp işleminde otomatik tekrar denenecek): ${err.message}"
                    showStatus("Ayarlar kaydedildi.", isError = false)
                }
            }
        }
    }

    private fun refreshAccessibilityStatus() {
        val enabled = WhatsAppTranslatorAccessibilityService.isEnabled(this)
        if (enabled) {
            txtServiceStatusTitle.text = "Erişilebilirlik Servisi Aktif"
            txtServiceStatusTitle.setTextColor(Color.parseColor("#69416F"))
            txtServiceStatusDesc.text = "WhatsApp içinde mesaja dokununca Çeviri butonu ve üst ekran otomatik görünür."
            btnEnableAccess.text = "İzin Ayarları"
        } else {
            txtServiceStatusTitle.text = "Erişilebilirlik İzni Gerekli"
            txtServiceStatusTitle.setTextColor(Color.parseColor("#1A1A1A"))
            txtServiceStatusDesc.text = "WhatsApp mesajlarını okumak ve onay sonrası cevap kutusuna yazabilmek için izni açın."
            btnEnableAccess.text = "İzni Etkinleştir"
        }
    }

    private fun showStatus(message: String, isError: Boolean) {
        txtFormStatus.visibility = View.VISIBLE
        txtFormStatus.text = message
        txtFormStatus.setTextColor(Color.parseColor(if (isError) "#B42318" else "#1A1A1A"))
    }

    private fun checkRemoteUpdate(manual: Boolean) {
        btnOtaAction.isEnabled = false
        if (manual) txtOtaStatus.text = "OTA sunucusu kontrol ediliyor..."

        executor.execute {
            val res = runCatching {
                GitHubUpdateChecker.checkForUpdate(currentVersionName, currentVersionCode)
            }
            mainHandler.post {
                btnOtaAction.isEnabled = true
                res.onSuccess { remote ->
                    if (remote != null) {
                        pendingRelease = remote
                        txtOtaStatus.setTextColor(Color.parseColor("#69416F"))
                        txtOtaStatus.text = "Yeni sürüm hazır: v${remote.versionName}"
                        btnOtaAction.text = "Şimdi Güncelle"
                        btnOtaAction.setBackgroundResource(R.drawable.bg_btn_primary)
                        btnOtaAction.setTextColor(Color.WHITE)
                    } else {
                        pendingRelease = null
                        txtOtaStatus.setTextColor(Color.parseColor("#1A1A1A"))
                        txtOtaStatus.text = "Sürüm güncel (v$currentVersionName)."
                        btnOtaAction.text = "Denetle"
                        btnOtaAction.setBackgroundResource(R.drawable.bg_btn_secondary)
                        btnOtaAction.setTextColor(Color.parseColor("#69416F"))
                    }
                }.onFailure {
                    txtOtaStatus.setTextColor(Color.parseColor("#1A1A1A"))
                    txtOtaStatus.text = "Sürüm v$currentVersionName (OTA bağlantısı bekleniyor)."
                    btnOtaAction.text = "Denetle"
                }
            }
        }
    }

    private fun downloadAndInstallUpdate(release: RemoteReleaseInfo) {
        btnOtaAction.isEnabled = false
        btnOtaAction.text = "İndiriliyor..."
        txtOtaStatus.setTextColor(Color.parseColor("#69416F"))
        txtOtaStatus.text = "v${release.versionName} indiriliyor..."

        executor.execute {
            val res = runCatching {
                GitHubUpdateChecker.downloadApk(this, release.downloadUrl)
            }
            mainHandler.post {
                btnOtaAction.isEnabled = true
                btnOtaAction.text = "Şimdi Güncelle"
                res.onSuccess { apkFile ->
                    txtOtaStatus.text = "İndirme tamamlandı, kurulum açılıyor..."
                    GitHubUpdateChecker.launchApkInstaller(this, apkFile)
                }.onFailure { err ->
                    txtOtaStatus.setTextColor(Color.parseColor("#B42318"))
                    txtOtaStatus.text = "İndirme hatası: ${err.message}"
                }
            }
        }
    }
}
