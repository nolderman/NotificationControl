package com.personseenalert

import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private var selectedRule: AlertRule = AlertRule.PREDEFINED[0]

    private lateinit var switchEnabled: SwitchCompat
    private lateinit var switchSound: SwitchCompat
    private lateinit var switchVibrate: SwitchCompat
    private lateinit var switchSchedule: SwitchCompat
    private lateinit var tvStartTime: TextView
    private lateinit var tvEndTime: TextView
    private lateinit var layoutSchedule: View
    private lateinit var btnGrantAccess: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)

        switchEnabled = findViewById(R.id.switchEnabled)
        switchSound = findViewById(R.id.switchSound)
        switchVibrate = findViewById(R.id.switchVibrate)
        switchSchedule = findViewById(R.id.switchSchedule)
        tvStartTime = findViewById(R.id.tvStartTime)
        tvEndTime = findViewById(R.id.tvEndTime)
        layoutSchedule = findViewById(R.id.layoutSchedule)
        btnGrantAccess = findViewById(R.id.btnGrantAccess)

        btnGrantAccess.setOnClickListener {
            startActivity(android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        initDefaults()
        setupSchedule()

        val savedId = savedInstanceState?.getString("selected_rule_id")
        selectedRule = (savedId?.let { AlertRule.findById(it) }) ?: AlertRule.PREDEFINED[0]
        val initialIndex = AlertRule.PREDEFINED.indexOf(selectedRule).coerceAtLeast(0)
        setupSpinner(initialIndex)

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("selected_rule_id", selectedRule.id)
    }

    private fun initDefaults() {
        val editor = prefs.edit()
        var changed = false
        for (rule in AlertRule.PREDEFINED) {
            val key = "rule_${rule.id}_enabled"
            if (!prefs.contains(key)) {
                editor.putBoolean("rule_${rule.id}_enabled", true)
                    .putBoolean("rule_${rule.id}_sound", rule.defaultSound)
                    .putBoolean("rule_${rule.id}_vibrate", rule.defaultVibrate)
                    .putBoolean("rule_${rule.id}_schedule_enabled", false)
                    .putInt("rule_${rule.id}_schedule_start_hour", 22)
                    .putInt("rule_${rule.id}_schedule_start_minute", 0)
                    .putInt("rule_${rule.id}_schedule_end_hour", 7)
                    .putInt("rule_${rule.id}_schedule_end_minute", 0)
                changed = true
            }
        }
        if (changed) editor.apply()
    }

    private fun setupSpinner(initialSelection: Int) {
        val spinner = findViewById<Spinner>(R.id.spinnerRule)
        val names = AlertRule.PREDEFINED.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(initialSelection)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position !in AlertRule.PREDEFINED.indices) return
                selectedRule = AlertRule.PREDEFINED[position]
                loadRuleSettings()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupSwitches() {
        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("rule_${selectedRule.id}_enabled", isChecked).apply()
            updateDependentControls(isChecked)
        }
        switchSound.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("rule_${selectedRule.id}_sound", isChecked).apply()
        }
        switchVibrate.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("rule_${selectedRule.id}_vibrate", isChecked).apply()
        }
        switchSchedule.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("rule_${selectedRule.id}_schedule_enabled", isChecked).apply()
            layoutSchedule.visibility =
                if (isChecked && switchEnabled.isChecked) View.VISIBLE else View.GONE
        }
    }

    private fun setupSchedule() {
        tvStartTime.setOnClickListener {
            val ruleId = selectedRule.id
            val h = prefs.getInt("rule_${ruleId}_schedule_start_hour", 22)
            val m = prefs.getInt("rule_${ruleId}_schedule_start_minute", 0)
            TimePickerDialog(this, { _, hour, minute ->
                prefs.edit()
                    .putInt("rule_${ruleId}_schedule_start_hour", hour)
                    .putInt("rule_${ruleId}_schedule_start_minute", minute)
                    .apply()
                if (selectedRule.id == ruleId) {
                    updateTimeDisplay(tvStartTime, hour, minute)
                }
            }, h, m, DateFormat.is24HourFormat(this)).show()
        }

        tvEndTime.setOnClickListener {
            val ruleId = selectedRule.id
            val h = prefs.getInt("rule_${ruleId}_schedule_end_hour", 7)
            val m = prefs.getInt("rule_${ruleId}_schedule_end_minute", 0)
            TimePickerDialog(this, { _, hour, minute ->
                prefs.edit()
                    .putInt("rule_${ruleId}_schedule_end_hour", hour)
                    .putInt("rule_${ruleId}_schedule_end_minute", minute)
                    .apply()
                if (selectedRule.id == ruleId) {
                    updateTimeDisplay(tvEndTime, hour, minute)
                }
            }, h, m, DateFormat.is24HourFormat(this)).show()
        }
    }

    private fun loadRuleSettings() {
        val id = selectedRule.id
        val name = selectedRule.name

        switchEnabled.setOnCheckedChangeListener(null)
        switchSound.setOnCheckedChangeListener(null)
        switchVibrate.setOnCheckedChangeListener(null)
        switchSchedule.setOnCheckedChangeListener(null)

        val enabled = prefs.getBoolean("rule_${id}_enabled", false)
        val scheduleEnabled = prefs.getBoolean("rule_${id}_schedule_enabled", false)

        switchEnabled.isChecked = enabled
        switchEnabled.text = getString(R.string.switch_enabled_label, name)
        switchSound.isChecked = prefs.getBoolean("rule_${id}_sound", false)
        switchSound.text = getString(R.string.switch_sound_label, name)
        switchVibrate.isChecked = prefs.getBoolean("rule_${id}_vibrate", false)
        switchVibrate.text = getString(R.string.switch_vibrate_label, name)
        switchSchedule.isChecked = scheduleEnabled
        switchSchedule.text = getString(R.string.switch_schedule_label, name)

        updateTimeDisplay(tvStartTime,
            prefs.getInt("rule_${id}_schedule_start_hour", 22),
            prefs.getInt("rule_${id}_schedule_start_minute", 0))
        updateTimeDisplay(tvEndTime,
            prefs.getInt("rule_${id}_schedule_end_hour", 7),
            prefs.getInt("rule_${id}_schedule_end_minute", 0))

        updateDependentControls(enabled)
        layoutSchedule.visibility = if (scheduleEnabled && enabled) View.VISIBLE else View.GONE

        setupSwitches()
    }

    private fun updateDependentControls(enabled: Boolean) {
        switchSound.isEnabled = enabled
        switchVibrate.isEnabled = enabled
        switchSchedule.isEnabled = enabled
        val alpha = if (enabled) 1.0f else 0.5f
        switchSound.alpha = alpha
        switchVibrate.alpha = alpha
        switchSchedule.alpha = alpha
        layoutSchedule.alpha = alpha
    }

    private fun updateTimeDisplay(tv: TextView, hour: Int, minute: Int) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        tv.text = DateFormat.getTimeFormat(this).format(cal.time)
    }

    private fun updateStatus() {
        val enabled = isNotificationListenerEnabled()
        val statusText = findViewById<TextView>(R.id.tvStatus)
        statusText.text = if (enabled) {
            getString(R.string.status_active)
        } else {
            getString(R.string.status_not_granted)
        }
        btnGrantAccess.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        val myComponent = ComponentName(this, AlertListenerService::class.java).flattenToString()
        return flat.split(":").any { it == myComponent }
    }
}
