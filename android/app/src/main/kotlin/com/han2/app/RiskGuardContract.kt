package com.han2.app

object RiskGuardContract {
    const val ACTION_RISK_STATE = "com.han2.app.action.RISK_STATE"
    const val ACTION_CONFIRM_SEND = "com.han2.app.action.CONFIRM_SEND"
    const val ACTION_CANCEL_SEND = "com.han2.app.action.CANCEL_SEND"
    const val ACTION_EXTERNAL_SEND_COMPLETED = "com.han2.app.action.EXTERNAL_SEND_COMPLETED"
    const val ACTION_REQUEST_CONFIRMATION = "com.han2.app.action.REQUEST_CONFIRMATION"
    const val ACTION_SEND_TARGET_STATE = "com.han2.app.action.SEND_TARGET_STATE"
    const val EXTRA_DANGER = "danger"
    const val EXTRA_HAS_DRAFT = "has_draft"
    const val EXTRA_RISK_LEVEL = "risk_level"
    const val EXTRA_APP_PACKAGE = "app_package"
    const val EXTRA_DRAFT_ID = "draft_id"
    const val EXTRA_RISK_PATTERN = "risk_pattern"
    const val EXTRA_INPUT_PREVIEW = "input_preview"
    const val EXTRA_HAS_EXTERNAL_SEND = "has_external_send"
    const val EXTRA_SOURCE = "source"
    const val SOURCE_KEYBOARD = "keyboard"
    const val SOURCE_ACCESSIBILITY = "accessibility"
}
