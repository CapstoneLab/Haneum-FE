package com.han2.app

import android.content.Intent
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NativeMainActivity : AppCompatActivity() {
    @Suppress("DEPRECATION")
    private val preferences by lazy { getSharedPreferences(KeyboardPreferences.NAME, MODE_MULTI_PROCESS) }
    private lateinit var content: LinearLayout
    private lateinit var statusChip: TextView
    private lateinit var heroDescription: TextView
    private lateinit var setupGuide: LinearLayout
    private lateinit var backendStatus: TextView
    private val setupStepIndicators = mutableListOf<TextView>()
    private val protectionDependentSwitches = mutableListOf<SwitchCompat>()
    private val switches = mutableMapOf<String, SwitchCompat>()
    private var syncingSettings = false

    private val navy = Color.rgb(26, 35, 50)
    private val muted = Color.rgb(139, 148, 165)
    private val purple = Color.rgb(105, 94, 232)
    private val page = Color.rgb(246, 247, 251)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(36))
        }
        buildScreen()
        setContentView(ScrollView(this).apply {
            setBackgroundColor(page)
            isFillViewport = true
            addView(content)
        })
        syncSettingsFromBackend()
    }

    override fun onResume() {
        super.onResume()
        updateKeyboardStatus()
    }

    private fun buildScreen() {
        content.removeAllViews()
        protectionDependentSwitches.clear()
        switches.clear()
        setupStepIndicators.clear()
        val header = horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(vertical().apply {
                addView(label("키보드 설정", 30f, navy, true))
                addView(label("입력에 필요한 기능만 간편하게 관리하세요.", 14f, muted))
            }, LinearLayout.LayoutParams(0, wrap, 1f))
            statusChip = label("선택 필요", 12f, Color.rgb(92, 98, 117), true).apply {
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = rounded(Color.WHITE, 18f)
            }
            addView(statusChip)
        }
        content.addView(header)
        content.addView(space(18))

        val hero = horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(16), dp(14), dp(16))
            background = rounded(purple, 18f)
            addView(icon("⌨", Color.rgb(126, 116, 237), Color.WHITE))
            addView(vertical().apply {
                setPadding(dp(12), 0, dp(8), 0)
                addView(label("HAN2 키보드", 17f, Color.WHITE, true))
                heroDescription = label("HAN2를 기본 키보드로 선택해 주세요", 12f, Color.rgb(225, 222, 255))
                addView(heroDescription)
            }, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(actionLabel("선택하기") {
                val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                val enabled = manager.enabledInputMethodList.any { it.packageName == packageName }
                if (enabled) manager.showInputMethodPicker()
                else startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            })
        }
        content.addView(hero)
        backendStatus = label("백엔드 확인 중…", 12f, muted, true).apply {
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(Color.WHITE, 12f)
        }
        content.addView(space(8))
        content.addView(backendStatus, matchWrap())
        content.addView(space(12))

        setupGuide = vertical().apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = borderedCard()
            addView(label("3단계로 키보드를 연결하세요", 15f, navy, true))
            addView(space(12))
            addView(setupStep("1", "HAN2 안전 키보드 켜기", "Android 키보드 목록에서 활성화") {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            })
            addView(space(10))
            addView(setupStep("2", "기본 키보드로 선택", "키보드 선택 창에서 HAN2 선택") {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            })
            addView(space(10))
            addView(setupStep("3", "전송 보호 접근성 켜기", "앱의 전송 버튼을 누를 때 다시 확인합니다") {
                openAccessibilityServiceSettings()
            })
        }
        content.addView(setupGuide)

        content.addView(section("핵심 기능"))
        val coreCard = settingsCard()
        coreCard.addView(toggleRow("✓", Color.rgb(57, 190, 132), "안전 입력 보호", "앱을 닫아도 키보드에서 계속 감지해요", KeyboardPreferences.PROTECTION, true, false))
        coreCard.addView(divider())
        coreCard.addView(toggleRow("✦", purple, "문장 추천", "입력에 맞는 다음 문장을 보여줘요", KeyboardPreferences.SUGGESTIONS, true))
        coreCard.addView(divider())
        coreCard.addView(toggleRow("↔", Color.rgb(65, 153, 226), "키 입력 진동", "키를 누를 때 가볍게 진동해요", KeyboardPreferences.HAPTICS, true))
        coreCard.addView(divider())
        coreCard.addView(toggleRow("◖", Color.rgb(249, 158, 68), "키 입력 소리", "키를 누를 때 소리를 재생해요", KeyboardPreferences.SOUND, false))
        coreCard.addView(divider())
        coreCard.addView(scopeNavigationRow())
        content.addView(coreCard)

        content.addView(section("키보드 모양"))
        val appearanceCard = settingsCard()
        appearanceCard.addView(infoRow("◉", Color.rgb(221, 103, 157), "테마", "키보드 화면 스타일", "시스템"))
        appearanceCard.addView(divider())
        appearanceCard.addView(infoRow("#", Color.rgb(135, 148, 169), "숫자·기호", "키보드의 ?123 키로 전환", "표준 배열"))
        content.addView(appearanceCard)

        content.addView(space(14))
        content.addView(clickableCard("ⓘ", "키보드 앱 업데이트 정보", "현재 버전과 업데이트 확인") {
            showAppInfo()
        })
        content.addView(space(12))
        content.addView(clickableCard("▤", "보고서 확인", "감지된 위험과 판단 이유를 확인하세요") {
            showDetectionReport()
        }.apply { background = rounded(Color.rgb(51, 59, 78), 16f) })
        content.addView(label("설정은 이 기기에 안전하게 저장됩니다.", 12f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        }, matchWrap())
        updateFeatureAvailability()
    }

    private fun scopeNavigationRow(): View = horizontal().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(13), dp(12), dp(13))
        addView(icon("▦", Color.rgb(73, 124, 220), Color.WHITE))
        addView(vertical().apply {
            setPadding(dp(12), 0, dp(6), 0)
            addView(label("설치된 앱 감지 설정", 16f, navy, true))
            addView(label("기본·사용자 설치 앱별로 감지를 설정해요", 12f, muted))
        }, LinearLayout.LayoutParams(0, wrap, 1f))
        addView(navigationArrow(purple))
        setOnClickListener { showAppScopeScreen() }
    }

    @Suppress("DEPRECATION")
    private fun showAppScopeScreen() {
        content.removeAllViews()
        content.addView(subPageHeader("설치된 앱 감지 설정") {
            buildScreen()
            syncSettingsFromBackend()
        })
        content.addView(space(16))
        content.addView(label("감지할 앱 선택", 20f, navy, true))
        content.addView(label("켜진 앱에서만 민감정보를 감지합니다.", 14f, muted).apply {
            setPadding(0, dp(4), 0, dp(16))
        })

        val launchablePackages = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0
        ).mapTo(mutableSetOf()) { it.activityInfo.packageName }
        val apps = packageManager.getInstalledApplications(0)
            .filter { app ->
                val userInstalled = app.flags and ApplicationInfo.FLAG_SYSTEM == 0
                app.packageName != packageName &&
                    (userInstalled || app.packageName in launchablePackages)
            }
            .sortedBy { packageManager.getApplicationLabel(it).toString().lowercase(Locale.getDefault()) }
        val card = settingsCard()
        apps.forEachIndexed { index, app ->
            val appName = packageManager.getApplicationLabel(app).toString()
            val key = KeyboardPreferences.APP_SCOPE_PREFIX + app.packageName
            card.addView(horizontal().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(10), dp(10))
                addView(ImageView(this@NativeMainActivity).apply {
                    setImageDrawable(packageManager.getApplicationIcon(app))
                    contentDescription = appName
                }, LinearLayout.LayoutParams(dp(42), dp(42)))
                addView(vertical().apply {
                    setPadding(dp(12), 0, dp(8), 0)
                    addView(label(appName, 15f, navy, true))
                    addView(label(app.packageName, 10f, muted))
                }, LinearLayout.LayoutParams(0, wrap, 1f))
                addView(SwitchCompat(this@NativeMainActivity).apply {
                    isChecked = preferences.getBoolean(key, true)
                    contentDescription = "$appName 감지"
                    setOnCheckedChangeListener { _, checked ->
                        preferences.edit().putBoolean(key, checked).apply()
                    }
                })
            })
            if (index != apps.lastIndex) card.addView(divider())
        }
        content.addView(card)
        content.addView(label("총 ${apps.size}개 앱", 12f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(24))
        })
    }

    private fun updateKeyboardStatus() {
        val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = manager.enabledInputMethodList.any { it.packageName == packageName }
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith(packageName) == true
        val accessibilityEnabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty().split(':').any { flattenedName ->
            ComponentName.unflattenFromString(flattenedName)?.let { component ->
                component.packageName == packageName &&
                    component.className == RiskSendGuardService::class.java.name
            } == true
        }
        val protectionEnabled = preferences.getBoolean(KeyboardPreferences.PROTECTION, true)
        updateSetupProgress(enabled, selected, accessibilityEnabled)
        when {
            !protectionEnabled -> {
                statusChip.text = "○ 보호 꺼짐"
                statusChip.setTextColor(Color.rgb(139, 148, 165))
                heroDescription.text = "안전 입력 보호와 위험 감지가 비활성화됐어요"
                setupGuide.visibility = if (enabled && selected && accessibilityEnabled) View.GONE else View.VISIBLE
            }
            enabled && selected && accessibilityEnabled -> {
                statusChip.text = "● 설정 완료"
                statusChip.setTextColor(Color.rgb(42, 151, 102))
                heroDescription.text = "다른 앱에서도 안전 입력 보호가 동작해요"
                setupGuide.visibility = View.GONE
            }
            selected -> {
                statusChip.text = "● 접근성 필요"
                statusChip.setTextColor(Color.rgb(155, 104, 47))
                heroDescription.text = "마지막으로 전송 보호 접근성을 켜 주세요"
                setupGuide.visibility = View.VISIBLE
            }
            enabled -> {
                statusChip.text = "● 선택 필요"
                statusChip.setTextColor(purple)
                heroDescription.text = "HAN2를 기본 키보드로 선택해 주세요"
                setupGuide.visibility = View.VISIBLE
            }
            else -> {
                statusChip.text = "● 설정 필요"
                statusChip.setTextColor(Color.rgb(155, 104, 47))
                heroDescription.text = "먼저 시스템에서 HAN2 키보드를 켜 주세요"
                setupGuide.visibility = View.VISIBLE
            }
        }
    }

    private fun updateSetupProgress(enabled: Boolean, selected: Boolean, accessibilityEnabled: Boolean) {
        val completedSteps = when {
            !enabled -> 0
            !selected -> 1
            !accessibilityEnabled -> 2
            else -> 3
        }
        setupStepIndicators.forEachIndexed { index, indicator ->
            val step = index + 1
            when {
                step <= completedSteps -> {
                    indicator.setTextColor(Color.rgb(54, 161, 116))
                    indicator.background = rounded(Color.rgb(228, 248, 239), 18f)
                }
                step == completedSteps + 1 -> {
                    indicator.setTextColor(Color.WHITE)
                    indicator.background = rounded(purple, 18f)
                }
                else -> {
                    indicator.setTextColor(muted)
                    indicator.background = rounded(Color.rgb(238, 240, 246), 18f)
                }
            }
        }
    }

    private fun openAccessibilityServiceSettings() {
        if (!preferences.getBoolean(KeyboardPreferences.ACCESSIBILITY_DISCLOSURE_ACCEPTED, false)) {
            AlertDialog.Builder(this)
                .setTitle("전송 보호 접근성 사용 안내")
                .setMessage(
                    "HAN2는 다른 앱의 전송 버튼을 찾아 개인정보가 포함된 메시지가 바로 전송되지 않도록 보호합니다. " +
                        "이를 위해 현재 화면의 버튼 정보와 입력 상태를 확인합니다. 입력 원문은 서버로 보내거나 저장하지 않습니다. " +
                        "동의하면 시스템 설정에서 ‘HAN2 전송 보호’를 직접 켜 주세요."
                )
                .setNegativeButton("취소", null)
                .setPositiveButton("동의하고 설정 열기") { _, _ ->
                    preferences.edit()
                        .putBoolean(KeyboardPreferences.ACCESSIBILITY_DISCLOSURE_ACCEPTED, true)
                        .apply()
                    launchAccessibilitySettings()
                }
                .show()
            return
        }
        launchAccessibilitySettings()
    }

    private fun launchAccessibilitySettings() {
        val component = ComponentName(this, RiskSendGuardService::class.java)
        val detailsIntent = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
            putExtra(Intent.EXTRA_COMPONENT_NAME, component)
        }
        runCatching { startActivity(detailsIntent) }
            .onFailure { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private fun toggleRow(
        symbol: String,
        color: Int,
        title: String,
        description: String,
        key: String,
        default: Boolean,
        requiresProtection: Boolean = true
    ): View {
        return horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(13), dp(8), dp(13))
            addView(icon(symbol, color, Color.WHITE))
            addView(vertical().apply {
                setPadding(dp(12), 0, dp(6), 0)
                addView(label(title, 16f, navy, true))
                addView(label(description, 12f, muted))
            }, LinearLayout.LayoutParams(0, wrap, 1f))
            val toggle = SwitchCompat(this@NativeMainActivity).apply {
                isChecked = preferences.getBoolean(key, default)
                setOnCheckedChangeListener { _, checked ->
                    preferences.edit().putBoolean(key, checked).apply()
                    if (key == KeyboardPreferences.PROTECTION) {
                        updateFeatureAvailability()
                        updateKeyboardStatus()
                    }
                    if (!syncingSettings && KeyBackApi.configured) KeyBackApi.updateSettings(this@NativeMainActivity)
                }
                contentDescription = title
            }
            if (requiresProtection) protectionDependentSwitches.add(toggle)
            switches[key] = toggle
            addView(toggle)
        }
    }

    private fun syncSettingsFromBackend() {
        if (!KeyBackApi.configured) {
            backendStatus.text = "백엔드 설정 필요 · KEYBACK_API_KEY를 입력하세요"
            backendStatus.setTextColor(Color.rgb(174, 83, 55))
            return
        }
        KeyBackApi.health { health ->
            runOnUiThread {
                health.fold(
                    onSuccess = {
                        backendStatus.text = "● KeyBack 연결됨 · $it"
                        backendStatus.setTextColor(Color.rgb(42, 151, 102))
                    },
                    onFailure = {
                        backendStatus.text = "KeyBack 연결 실패 · 로컬 보호 사용 중"
                        backendStatus.setTextColor(Color.rgb(174, 83, 55))
                    }
                )
            }
        }
        KeyBackApi.getSettings(this) { result ->
            result.onSuccess { settings ->
                runOnUiThread {
                    syncingSettings = true
                    preferences.edit()
                        .putString(KeyboardPreferences.RISK_SENSITIVITY, settings.riskSensitivity)
                        .putBoolean(KeyboardPreferences.HAPTICS, settings.vibrationEnabled)
                        .putBoolean(KeyboardPreferences.TTS, settings.ttsEnabled)
                        .putBoolean(KeyboardPreferences.LARGE_TEXT, settings.largeTextEnabled)
                        .putBoolean(KeyboardPreferences.HIGH_CONTRAST, settings.highContrastEnabled)
                        .apply()
                    switches[KeyboardPreferences.HAPTICS]?.isChecked = settings.vibrationEnabled
                    syncingSettings = false
                }
            }
        }
    }

    private fun updateFeatureAvailability() {
        val enabled = preferences.getBoolean(KeyboardPreferences.PROTECTION, true)
        protectionDependentSwitches.forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.4f
        }
    }

    private fun infoRow(symbol: String, color: Int, title: String, description: String, value: String): View = horizontal().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(13), dp(12), dp(13))
        addView(icon(symbol, color, Color.WHITE))
        addView(vertical().apply {
            setPadding(dp(12), 0, dp(6), 0)
            addView(label(title, 16f, navy, true))
            addView(label(description, 12f, muted))
        }, LinearLayout.LayoutParams(0, wrap, 1f))
        addView(label(value, 13f, purple, true))
    }

    private fun clickableCard(symbol: String, title: String, description: String, action: () -> Unit): View = horizontal().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = rounded(Color.WHITE, 16f)
        addView(icon(symbol, Color.rgb(132, 145, 165), Color.WHITE))
        addView(vertical().apply {
            setPadding(dp(12), 0, dp(6), 0)
            addView(label(title, 16f, if (title == "보고서 확인") Color.WHITE else navy, true))
            addView(label(description, 12f, if (title == "보고서 확인") Color.rgb(199, 204, 218) else muted))
        }, LinearLayout.LayoutParams(0, wrap, 1f))
        addView(navigationArrow(if (title == "보고서 확인") Color.WHITE else muted))
        setOnClickListener { action() }
    }

    private fun setupStep(number: String, title: String, description: String, action: () -> Unit): View = horizontal().apply {
        gravity = Gravity.CENTER_VERTICAL
        val indicator = label(number, 13f, muted, true).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(238, 240, 246), 18f)
        }
        setupStepIndicators.add(indicator)
        addView(indicator, LinearLayout.LayoutParams(dp(30), dp(30)))
        addView(vertical().apply {
            setPadding(dp(12), 0, 0, 0)
            addView(label(title, 14f, navy, true))
            addView(label(description, 11f, muted))
        }, LinearLayout.LayoutParams(0, wrap, 1f))
        setOnClickListener { action() }
    }

    private fun showDetectionReport() {
        if (KeyBackApi.configured) {
            showRemoteDetectionReport()
            return
        }
        val reports = DetectionStore.load(this)
        val list = vertical().apply {
            setPadding(dp(20), dp(6), dp(20), dp(12))
            if (reports.isEmpty()) {
                addView(label("✓", 30f, Color.rgb(54, 174, 119), true).apply { gravity = Gravity.CENTER })
                addView(label("아직 감지된 위험이 없어요", 16f, navy, true).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, dp(18))
                })
            } else {
                addView(label("최근 감지 ${reports.size}건", 14f, muted, true).apply { setPadding(0, 0, 0, dp(8)) })
                val formatter = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)
                reports.forEach { report ->
                    addView(vertical().apply {
                        setPadding(dp(13), dp(12), dp(13), dp(12))
                        background = rounded(
                            if (report.level == RiskLevel.DANGER) Color.rgb(255, 235, 237) else Color.rgb(255, 247, 225),
                            12f
                        )
                        addView(horizontal().apply {
                            addView(label(if (report.level == RiskLevel.DANGER) "위험" else "주의", 12f,
                                if (report.level == RiskLevel.DANGER) Color.rgb(184, 52, 60) else Color.rgb(164, 101, 24), true),
                                LinearLayout.LayoutParams(0, wrap, 1f))
                            addView(label(formatter.format(Date(report.detectedAt)), 11f, muted))
                        })
                        addView(label(report.title, 15f, navy, true).apply { setPadding(0, dp(5), 0, dp(3)) })
                        addView(label(report.reason, 12f, Color.rgb(91, 99, 115)))
                    })
                    addView(space(8))
                }
            }
        }
        val scroll = ScrollView(this).apply { addView(list) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("보고서 확인")
            .setView(scroll)
            .setNegativeButton("닫기", null)
            .apply { if (reports.isNotEmpty()) setPositiveButton("기록 비우기", null) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                DetectionStore.clear(this)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showRemoteDetectionReport() {
        val list = vertical().apply {
            setPadding(dp(20), dp(10), dp(20), dp(14))
            addView(label("서버 기록을 불러오는 중…", 14f, muted))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("KeyBack 위험 보고서")
            .setView(ScrollView(this).apply { addView(list) })
            .setNegativeButton("닫기", null)
            .setPositiveButton("전체 삭제", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).visibility = View.GONE
        }
        dialog.show()
        KeyBackApi.getRiskLogs(this) { result ->
            runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                list.removeAllViews()
                result.fold(
                    onSuccess = { logs ->
                        if (logs.isEmpty()) {
                            list.addView(label("아직 서버에 저장된 위험 기록이 없어요", 15f, navy, true))
                        } else {
                            list.addView(label("서버 기록 ${logs.size}건", 14f, muted, true))
                            list.addView(space(8))
                            logs.forEach { log ->
                                list.addView(vertical().apply {
                                    setPadding(dp(13), dp(12), dp(13), dp(12))
                                    background = rounded(
                                        if (log.level == RiskLevel.DANGER) Color.rgb(255, 235, 237) else Color.rgb(255, 247, 225),
                                        12f
                                    )
                                    addView(label("${log.level.name} · 위험도 ${log.riskScore}", 13f, navy, true))
                                    addView(label(log.reason.ifBlank { "판단 이유 없음" }, 12f, Color.rgb(91, 99, 115)))
                                    addView(label(log.createdAt, 11f, muted))
                                })
                                list.addView(space(8))
                            }
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                                visibility = View.VISIBLE
                                setOnClickListener {
                                    isEnabled = false
                                    text = "삭제 중…"
                                    var remaining = logs.size
                                    logs.forEach { log ->
                                        KeyBackApi.deleteRiskLog(this@NativeMainActivity, log.id) {
                                            runOnUiThread {
                                                remaining--
                                                if (remaining == 0) dialog.dismiss()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    onFailure = {
                        list.addView(label("서버 기록을 불러오지 못했습니다.\n${it.message.orEmpty()}", 14f, Color.rgb(174, 83, 55), true))
                    }
                )
            }
        }
    }

    private fun showAppInfo() {
        val body = vertical().apply {
            setPadding(dp(24), dp(4), dp(24), dp(8))
            addView(label("현재 버전", 12f, muted, true))
            addView(label(BuildConfig.VERSION_NAME, 24f, navy, true).apply { setPadding(0, dp(3), 0, dp(16)) })
        }
        val updateStatus = label("업데이트 확인 중…", 14f, muted).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.rgb(241, 242, 248), 12f)
        }
        body.addView(updateStatus, matchWrap())
        body.addView(label("입력 원문 전체를 저장하지 않고 위험 단계와 유형만 기기에 기록합니다.", 12f, muted).apply {
            setPadding(0, dp(16), 0, 0)
        })

        var availableUpdate: AppUpdateInfo? = null
        val dialog = AlertDialog.Builder(this)
            .setTitle("키보드 앱 업데이트 정보")
            .setView(body)
            .setNegativeButton("닫기", null)
            .setPositiveButton("업데이트", null)
            .create()
        dialog.setOnShowListener {
            val updateButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            updateButton.visibility = View.GONE
            updateButton.setOnClickListener {
                availableUpdate?.let { update ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.apkUrl)))
                }
            }
            AppUpdateManager.check { result ->
                runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    result.fold(
                        onSuccess = { update ->
                            availableUpdate = update
                            if (update == null) {
                                updateStatus.text = "업데이트 서버 연결됨 · 현재 최신 버전 v${BuildConfig.VERSION_NAME}"
                                updateStatus.setTextColor(Color.rgb(52, 139, 98))
                                updateStatus.background = rounded(Color.rgb(231, 248, 239), 12f)
                            } else {
                                updateStatus.text = buildString {
                                    append("업데이트 버전이 있습니다 · v${update.versionName}")
                                    if (update.releaseNotes.isNotBlank()) append("\n${update.releaseNotes}")
                                }
                                updateStatus.setTextColor(purple)
                                updateStatus.background = rounded(Color.rgb(238, 235, 255), 12f)
                                updateButton.visibility = View.VISIBLE
                            }
                        },
                        onFailure = {
                            updateStatus.text = "업데이트 정보를 확인하지 못했습니다."
                            updateStatus.setTextColor(Color.rgb(174, 83, 55))
                            updateStatus.background = rounded(Color.rgb(255, 239, 232), 12f)
                        }
                    )
                }
            }
        }
        dialog.show()
    }

    private fun settingsCard() = vertical().apply { background = rounded(Color.WHITE, 16f) }
    private fun borderedCard() = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), Color.rgb(224, 227, 238))
    }
    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius.toInt()).toFloat() }
    private fun horizontal() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun label(textValue: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = textValue
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun icon(symbol: String, backgroundColor: Int, textColor: Int) = label(symbol, 18f, textColor, true).apply {
        gravity = Gravity.CENTER
        background = rounded(backgroundColor, 11f)
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
    }
    private fun actionLabel(textValue: String, action: () -> Unit) = label(textValue, 13f, purple, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(11), dp(14), dp(11))
        background = rounded(Color.WHITE, 12f)
        setOnClickListener { action() }
    }
    private fun navigationArrow(color: Int) = label("→", 32f, color, true).apply {
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(44), wrap)
    }
    private fun subPageHeader(title: String, onBack: () -> Unit) = horizontal().apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(label("‹", 24f, navy).apply {
            gravity = Gravity.CENTER
            contentDescription = "돌아가기"
            setOnClickListener { onBack() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(label(title, 20f, navy, true).apply { setPadding(dp(8), 0, 0, 0) })
    }
    private fun section(textValue: String) = label(textValue, 13f, muted, true).apply { setPadding(dp(4), dp(24), 0, dp(8)) }
    private fun divider() = View(this).apply { setBackgroundColor(Color.rgb(238, 240, 246)) }.also { it.layoutParams = LinearLayout.LayoutParams(match, dp(1)).apply { marginStart = dp(66) } }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(match, dp(height)) }
    private fun matchWrap() = LinearLayout.LayoutParams(match, wrap)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val match = LinearLayout.LayoutParams.MATCH_PARENT
        private const val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
