package com.han2.app;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.content.res.Configuration;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.SoundEffectConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Minimal native HAN2 IME. Risk analysis can be attached before commit/action later. */
public class Han2KeyboardService extends InputMethodService {
    private static final String PREFS = "han2_keyboard_preferences";
    private static final int[] INITIAL = {0,2,3,5,6,7,9,11,12,14,15,16,17,18};
    private static final int[] FINAL = {1,4,7,8,16,17,19,21,22,23,24,25,26,27};
    private static final String[] CONSONANTS = {"ㄱ","ㄴ","ㄷ","ㄹ","ㅁ","ㅂ","ㅅ","ㅇ","ㅈ","ㅊ","ㅋ","ㅌ","ㅍ","ㅎ"};
    private static final String[] VOWELS = {"ㅏ","ㅐ","ㅑ","ㅓ","ㅔ","ㅕ","ㅗ","ㅛ","ㅜ","ㅠ","ㅡ","ㅣ"};
    private static final int[] VOWEL_INDEX = {0,1,2,4,5,6,8,12,13,17,18,20};
    private final Map<String,Integer> consonantMap = new HashMap<>();
    private final Map<String,Integer> vowelMap = new HashMap<>();
    private LinearLayout keyboard;
    private TextView privacyLabel;
    private boolean korean = true;
    private boolean numeric = false;
    private boolean sensitiveField = false;
    private boolean protectionEnabled = true;
    private boolean hapticsEnabled = true;
    private boolean soundEnabled = false;
    private boolean numberRowEnabled = false;
    private boolean darkTheme = false;
    private int lead = -1, vowel = -1, tail = 0;
    private String lastDetectionSignature = "";
    private long lastDetectionAt = 0;
    private String currentRiskLevel = "safe";
    private String currentRiskText = "";
    private LinearLayout confirmationRow;
    private String actionLabel = "입력";

    @Override public void onCreate() {
        super.onCreate();
        for (int i=0;i<CONSONANTS.length;i++) consonantMap.put(CONSONANTS[i], i);
        for (int i=0;i<VOWELS.length;i++) vowelMap.put(VOWELS[i], VOWEL_INDEX[i]);
    }

    @Override public View onCreateInputView() {
        keyboard = new LinearLayout(this);
        keyboard.setOrientation(LinearLayout.VERTICAL);
        keyboard.setPadding(dp(5),dp(7),dp(5),dp(7));
        keyboard.setBackgroundColor(darkTheme ? Color.rgb(31,34,40) : Color.rgb(224,227,233));
        rebuildKeyboard();
        return keyboard;
    }

    /** Keep the HAN2 soft keyboard visible even when an emulator or Bluetooth keyboard is attached. */
    @Override public boolean onEvaluateInputViewShown() {
        return true;
    }

    /** Accept show requests from every compatible editor: native fields, browsers and WebViews. */
    @Override public boolean onShowInputRequested(int flags, boolean configChange) {
        return true;
    }

    @Override public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        resetComposition();
        currentRiskLevel = "safe";
        currentRiskText = "";
        removeConfirmation();
        actionLabel = actionLabelFor(info.imeOptions & EditorInfo.IME_MASK_ACTION);
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        sensitiveField = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
            || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            || variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        protectionEnabled = getSharedPreferences(PREFS, MODE_MULTI_PROCESS)
            .getBoolean("protection_enabled", true);
        hapticsEnabled = getSharedPreferences(PREFS, MODE_MULTI_PROCESS).getBoolean("haptics_enabled", true);
        soundEnabled = getSharedPreferences(PREFS, MODE_MULTI_PROCESS).getBoolean("sound_enabled", false);
        numberRowEnabled = getSharedPreferences(PREFS, MODE_MULTI_PROCESS).getBoolean("number_row_enabled", false);
        String theme = getSharedPreferences(PREFS, MODE_MULTI_PROCESS).getString("keyboard_theme", "system");
        boolean systemDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
        darkTheme = "dark".equals(theme) || ("system".equals(theme) && systemDark);
        if (keyboard != null) rebuildKeyboard();
    }

    private void rebuildKeyboard() {
        keyboard.removeAllViews();
        keyboard.setBackgroundColor(darkTheme ? Color.rgb(31,34,40) : Color.rgb(224,227,233));
        privacyLabel = new TextView(this);
        privacyLabel.setGravity(Gravity.CENTER_VERTICAL);
        privacyLabel.setPadding(dp(12),dp(5),dp(12),dp(5));
        privacyLabel.setTextSize(12);
        privacyLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        keyboard.addView(privacyLabel, new LinearLayout.LayoutParams(-1,dp(44)));
        if (numeric) {
            addRow(new String[]{"1","2","3"});
            addRow(new String[]{"4","5","6"});
            addRow(new String[]{"7","8","9"});
            addRow(new String[]{"한글","0","⌫","enter"});
        } else if (korean) {
            if (numberRowEnabled) addRow(new String[]{"1","2","3","4","5","6","7","8","9","0"});
            addRow(new String[]{"ㅂ","ㅈ","ㄷ","ㄱ","ㅅ","ㅛ","ㅕ","ㅑ","ㅐ","ㅔ"});
            addRow(new String[]{"ㅁ","ㄴ","ㅇ","ㄹ","ㅎ","ㅗ","ㅓ","ㅏ","ㅣ"});
            addRow(new String[]{"ㅋ","ㅌ","ㅊ","ㅍ","ㅠ","ㅜ","ㅡ","⌫"});
        } else {
            if (numberRowEnabled) addRow(new String[]{"1","2","3","4","5","6","7","8","9","0"});
            addRow(new String[]{"q","w","e","r","t","y","u","i","o","p"});
            addRow(new String[]{"a","s","d","f","g","h","j","k","l"});
            addRow(new String[]{"z","x","c","v","b","n","m","⌫"});
        }
        if (!numeric) addRow(new String[]{"123","한/영","space",".","enter"});
        updatePrivacyLabel();
    }

    private void updatePrivacyLabel() {
        if (privacyLabel == null) return;
        if (!protectionEnabled) setStatus("보호 기능 꺼짐", "HAN2 앱에서 안전 입력 보호를 켤 수 있어요", Color.rgb(100,108,120), Color.rgb(238,240,244));
        else if (sensitiveField) setStatus("보호 · 민감정보 입력 중", "분석과 저장을 중지했어요", Color.rgb(36,133,91), Color.rgb(230,248,239));
        else setStatus("감지 중", "", Color.rgb(35,130,91), Color.rgb(232,248,240));
    }

    private void setStatus(String title, String detail, int color, int background) {
        privacyLabel.setText(detail.isEmpty() ? title : title + "\n" + detail);
        privacyLabel.setTextColor(color);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(background);
        shape.setCornerRadius(dp(10));
        privacyLabel.setBackground(shape);
    }

    private void addRow(String[] keys) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        for (String key : keys) {
            TextView button = new TextView(this);
            button.setGravity(Gravity.CENTER);
            button.setText(key.equals("space") ? "공백" : key.equals("enter") ? actionLabel : key);
            button.setTextSize(key.length() > 2 ? 11 : 18);
            button.setTextColor(darkTheme ? Color.rgb(239,241,245) : Color.rgb(30,36,46));
            button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            GradientDrawable keyShape = new GradientDrawable();
            keyShape.setColor(darkTheme
                ? (key.equals("space") ? Color.rgb(67,71,80) : Color.rgb(51,55,64))
                : (key.equals("space") ? Color.WHITE : Color.rgb(248,249,251)));
            keyShape.setCornerRadius(dp(7));
            keyShape.setStroke(dp(1), darkTheme ? Color.rgb(89,94,105) : Color.rgb(190,195,204));
            button.setBackground(keyShape);
            button.setClickable(true);
            button.setOnClickListener(v -> {
                if (hapticsEnabled) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (soundEnabled) {
                    AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
                    audio.playSoundEffect(SoundEffectConstants.CLICK);
                }
                handleKey(key);
                keyboard.postDelayed(this::analyzeCurrentInput, 30);
            });
            float weight = key.equals("space") ? 3.5f : key.equals("enter") || key.equals("한/영") ? 1.5f : 1f;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,dp(48),weight);
            lp.setMargins(dp(2),dp(2),dp(2),dp(2));
            row.addView(button, lp);
        }
        keyboard.addView(row, new LinearLayout.LayoutParams(-1,dp(52)));
    }

    private void handleKey(String key) {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) return;
        if (!key.equals("enter")) removeConfirmation();
        if (key.equals("⌫")) { backspace(connection); return; }
        if (key.equals("한/영")) { finishComposition(connection); korean=!korean; numeric=false; rebuildKeyboard(); return; }
        if (key.equals("123")) { finishComposition(connection); numeric=true; rebuildKeyboard(); return; }
        if (key.equals("한글")) { finishComposition(connection); numeric=false; korean=true; rebuildKeyboard(); return; }
        if (key.equals("space")) { finishComposition(connection); connection.commitText(" ",1); return; }
        if (key.equals("enter")) {
            finishComposition(connection);
            analyzeCurrentInput();
            if ("danger".equals(currentRiskLevel)) showRiskConfirmation();
            else performEnter(connection);
            return;
        }
        if (key.equals(".")) { finishComposition(connection); connection.commitText(".",1); return; }
        if (korean && consonantMap.containsKey(key)) composeConsonant(connection,key);
        else if (korean && vowelMap.containsKey(key)) composeVowel(connection,key);
        else { finishComposition(connection); connection.commitText(key,1); }
    }

    private void analyzeCurrentInput() {
        if (privacyLabel == null || sensitiveField || !protectionEnabled) { updatePrivacyLabel(); return; }
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) return;
        CharSequence before = connection.getTextBeforeCursor(240, 0);
        String text = before == null ? "" : before.toString();

        if (text.matches("(?s).*(주민|비밀번호|OTP|인증번호).*[0-9]{4,}.*")
            || text.matches("(?s).*[0-9]{6}-?[0-9]{7}.*")) {
            currentRiskLevel = "danger"; currentRiskText = text;
            setStatus("위험 · 민감정보가 포함됐어요", "번호를 삭제하거나 가린 뒤 보내세요", Color.rgb(183,50,55), Color.rgb(255,233,234));
            recordDetection("danger", "민감정보 감지", "인증정보 또는 식별번호 형태가 포함됐어요", text);
        } else if (text.matches("(?s).*(송금|입금|계좌|상품권|대출).*")
            || text.matches("(?s).*(http://|bit\\.ly|tinyurl).*") ) {
            currentRiskLevel = "warning"; currentRiskText = text;
            setStatus("주의 · 금융사기 가능성을 확인하세요", "상대와 링크를 다시 확인한 뒤 보내세요", Color.rgb(170,103,21), Color.rgb(255,246,224));
            recordDetection("warning", "금융사기 주의", "송금 유도 표현 또는 안전하지 않은 링크가 포함됐어요", text);
        } else if (text.matches("(?s).*(죽여|협박|가만두지|자해|죽고 싶).*") ) {
            currentRiskLevel = "danger"; currentRiskText = text;
            setStatus("위험 · 심각한 표현이 감지됐어요", "보내기 전에 문장을 다시 확인하세요", Color.rgb(183,50,55), Color.rgb(255,233,234));
            recordDetection("danger", "위험 표현 감지", "위협·자해와 관련된 고위험 표현이 포함됐어요", text);
        } else if (text.matches("(?s).*(01[016789][- ]?[0-9]{3,4}[- ]?[0-9]{4}|[0-9]{2,6}[- ][0-9]{2,6}[- ][0-9]{2,6}).*")) {
            currentRiskLevel = "warning"; currentRiskText = text;
            setStatus("주의 · 개인정보로 보이는 번호예요", "공유해도 되는 정보인지 확인하세요", Color.rgb(170,103,21), Color.rgb(255,246,224));
            recordDetection("warning", "개인정보 주의", "전화번호 또는 개인식별 번호 형태가 포함됐어요", text);
        } else {
            currentRiskLevel = "safe"; currentRiskText = "";
            setStatus("감지 중", "", Color.rgb(35,130,91), Color.rgb(232,248,240));
        }
    }

    private void performEnter(InputConnection connection) {
        int action = getCurrentInputEditorInfo().imeOptions & EditorInfo.IME_MASK_ACTION;
        if (action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) {
            connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        } else connection.performEditorAction(action);
    }

    private void showRiskConfirmation() {
        if (confirmationRow != null || keyboard == null) return;
        setStatus("위험으로 판단되어 " + actionLabel + "을 중지했습니다", "정말로 이 내용을 진행하시겠습니까?", Color.rgb(183,50,55), Color.rgb(255,226,228));
        confirmationRow = new LinearLayout(this);
        confirmationRow.setPadding(0,dp(4),0,dp(4));
        TextView revise = confirmationButton("수정하기", false);
        TextView proceed = confirmationButton("그래도 진행", true);
        revise.setOnClickListener(v -> {
            removeConfirmation();
            setStatus("전송을 중지했어요", "내용을 안전하게 수정해 주세요", Color.rgb(35,130,91), Color.rgb(232,248,240));
        });
        proceed.setOnClickListener(v -> {
            InputConnection active = getCurrentInputConnection();
            if (active == null) return;
            recordDetection("danger", "경고 후 진행", "위험 경고를 확인한 뒤 사용자가 입력을 진행했어요", currentRiskText);
            removeConfirmation();
            performEnter(active);
        });
        confirmationRow.addView(revise, confirmationLayout());
        confirmationRow.addView(proceed, confirmationLayout());
        keyboard.addView(confirmationRow, 1, new LinearLayout.LayoutParams(-1,dp(50)));
    }

    private TextView confirmationButton(String label, boolean danger) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(12);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(danger ? Color.WHITE : Color.rgb(73,81,94));
        GradientDrawable background = new GradientDrawable();
        background.setColor(danger ? Color.rgb(193,57,64) : Color.WHITE);
        background.setCornerRadius(dp(9));
        background.setStroke(dp(1), danger ? Color.rgb(193,57,64) : Color.rgb(190,195,204));
        button.setBackground(background);
        return button;
    }

    private LinearLayout.LayoutParams confirmationLayout() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,dp(42),1f);
        params.setMargins(dp(3),0,dp(3),0);
        return params;
    }

    private void removeConfirmation() {
        if (confirmationRow != null && confirmationRow.getParent() == keyboard) keyboard.removeView(confirmationRow);
        confirmationRow = null;
    }

    private String actionLabelFor(int action) {
        switch (action) {
            case EditorInfo.IME_ACTION_SEARCH: return "검색";
            case EditorInfo.IME_ACTION_SEND: return "전송";
            case EditorInfo.IME_ACTION_GO: return "진행";
            case EditorInfo.IME_ACTION_NEXT: return "다음";
            case EditorInfo.IME_ACTION_PREVIOUS: return "이전";
            case EditorInfo.IME_ACTION_DONE: return "완료";
            default: return "입력";
        }
    }

    private synchronized void recordDetection(String level, String title, String reason, String source) {
        long now = System.currentTimeMillis();
        String masked = source.replaceAll("[0-9]", "•").trim();
        if (masked.length() > 80) masked = "…" + masked.substring(masked.length() - 80);
        String signature = level + "|" + title + "|" + masked;
        if (signature.equals(lastDetectionSignature) && now - lastDetectionAt < 30000) return;
        lastDetectionSignature = signature;
        lastDetectionAt = now;
        try {
            String raw = getSharedPreferences(PREFS, MODE_MULTI_PROCESS).getString("detection_reports", "[]");
            JSONArray previous = new JSONArray(raw);
            JSONArray next = new JSONArray();
            JSONObject report = new JSONObject();
            report.put("id", String.valueOf(now));
            report.put("level", level);
            report.put("title", title);
            report.put("reason", reason);
            report.put("preview", masked.isEmpty() ? "입력 내용 비공개" : masked);
            report.put("detectedAt", now);
            next.put(report);
            for (int i=0; i<Math.min(previous.length(), 29); i++) next.put(previous.get(i));
            getSharedPreferences(PREFS, MODE_MULTI_PROCESS).edit()
                .putString("detection_reports", next.toString()).commit();
        } catch (Exception ignored) { }
    }

    private void composeConsonant(InputConnection c, String key) {
        int ci=consonantMap.get(key), nextLead=INITIAL[ci], nextTail=FINAL[ci];
        if (lead<0) { lead=nextLead; updateComposing(c); }
        else if (vowel<0) { finishComposition(c); lead=nextLead; updateComposing(c); }
        else if (tail==0) { tail=nextTail; updateComposing(c); }
        else { finishComposition(c); lead=nextLead; updateComposing(c); }
    }

    private void composeVowel(InputConnection c, String key) {
        int nextVowel=vowelMap.get(key);
        if (lead<0) { c.commitText(key,1); return; }
        if (vowel<0) { vowel=nextVowel; updateComposing(c); return; }
        if (tail>0) {
            int oldTail=tail; tail=0; updateComposing(c); c.finishComposingText();
            int ci=-1; for(int i=0;i<FINAL.length;i++) if(FINAL[i]==oldTail){ci=i;break;}
            lead=ci>=0?INITIAL[ci]:-1; vowel=nextVowel; tail=0; updateComposing(c); return;
        }
        finishComposition(c); c.commitText(key,1);
    }

    private void updateComposing(InputConnection c) {
        if (lead<0) return;
        if (vowel<0) {
            int ci=0; for(int i=0;i<INITIAL.length;i++) if(INITIAL[i]==lead){ci=i;break;}
            c.setComposingText(CONSONANTS[ci],1);
        } else c.setComposingText(String.valueOf((char)(0xAC00+(lead*21+vowel)*28+tail)),1);
    }

    private void finishComposition(InputConnection c) { c.finishComposingText(); resetComposition(); }
    private void resetComposition() { lead=-1; vowel=-1; tail=0; }
    private void backspace(InputConnection c) {
        if (tail>0) { tail=0; updateComposing(c); }
        else if (vowel>=0) { vowel=-1; updateComposing(c); }
        else if (lead>=0) { c.setComposingText("",1); c.finishComposingText(); resetComposition(); }
        else c.deleteSurroundingText(1,0);
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
}
