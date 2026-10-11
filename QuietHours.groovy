/** Quiet Hours 1.0.0 — Copyright 2026 Jon Wallace. MIT License. */
metadata {
    definition(name: "Quiet Hours", namespace: "jonw", author: "Jon Wallace",
        importUrl: "https://raw.githubusercontent.com/b69ca/hubitat-quiethours/main/QuietHours.groovy") {
        capability "Actuator"
        capability "Sensor"
        capability "Switch"
        capability "Refresh"
        capability "Initialize"
        capability "PushableButton"
        attribute "quietState", "enum", ["quiet", "normal"]
        attribute "status", "enum", ["ready", "configurationError"]
        attribute "stateReason", "enum", ["schedule", "override"]
        attribute "overrideState", "enum", ["none", "quiet", "normal"]
        attribute "overrideUntil", "string"
        attribute "nextScheduleBoundary", "string"
        attribute "scheduleZone", "string"
        attribute "lastSuccessfulUpdate", "string"
        command "forceQuiet", [[name: "Minutes", type: "NUMBER", description: "Blank uses default override duration"]]
        command "forceNormal", [[name: "Minutes", type: "NUMBER", description: "Blank uses default override duration"]]
        command "clearOverride"
    }
    preferences {
        input name: "startTime", type: "text", title: "Quiet time starts (24-hour HH:mm)", defaultValue: "22:00", required: true
        input name: "endTime", type: "text", title: "Quiet time ends (24-hour HH:mm)", defaultValue: "07:00", required: true
        input name: "startDays", type: "text", title: "Start weekdays (1=Mon through 7=Sun, comma separated)",
            defaultValue: "1,2,3,4,5,6,7", required: true
        input name: "overrideMinutes", type: "decimal", title: "Default manual override (minutes)", defaultValue: 60, range: "0.017..1440", required: true
        input name: "logEnable", type: "bool", title: "Debug logging for 30 minutes", defaultValue: false
    }
}
void installed() { initialize() }
void updated() { initialize() }
void initialize() {
    unschedule()
    emit("numberOfButtons", 3)
    if (settings.logEnable) runIn(1800, "logsOff")
    refresh()
}
void uninstalled() { unschedule() }
void logsOff() { device.updateSetting("logEnable", [value:"false", type:"bool"]) }
private Integer minute(value, String fallback) {
    String text = value == null ? fallback : value.toString().trim()
    if (!(text ==~ /([01][0-9]|2[0-3]):[0-5][0-9]/)) return null
    text.substring(0,2).toInteger() * 60 + text.substring(3,5).toInteger()
}
private BigDecimal duration(value) {
    try {
        BigDecimal n = new BigDecimal(value.toString())
        n >= 0.017G && n <= 1440G ? n : null
    } catch (Exception ignored) { null }
}
private Map config() {
    Integer start = minute(settings.startTime, "22:00")
    Integer end = minute(settings.endTime, "07:00")
    def raw = settings.startDays == null ? ["1","2","3","4","5","6","7"] : settings.startDays
    List days = raw instanceof Collection ? raw.collect { it.toString() } : raw.toString().split(",", -1).collect { it.trim() }
    BigDecimal minutes = duration(settings.overrideMinutes == null ? 60 : settings.overrideMinutes)
    if (start == null || end == null || start == end || !days || days.any { !(it in ["1","2","3","4","5","6","7"]) } || minutes == null) return null
    [start:start, end:end, days:days, minutes:minutes]
}
void on() { forceQuiet() }
void off() { forceNormal() }
void forceQuiet(value = null) { setOverride("quiet", value) }
void forceNormal(value = null) { setOverride("normal", value) }
private void setOverride(String mode, value) {
    Map c = config()
    if (!c) { emit("status", "configurationError"); return }
    BigDecimal minutes = value == null || value.toString().trim() == "" ? c.minutes : duration(value)
    if (minutes == null) { log.warn "Override minutes must be from 0.017 through 1440"; return }
    state.overrideMode = mode
    state.overrideEnd = now() + (minutes * 60000G).longValue()
    refresh()
}
void clearOverride() {
    state.overrideMode = null
    state.overrideEnd = null
    refresh()
}
private long boundary(Calendar date, int offset, int minutes, TimeZone zone) {
    Calendar day = (Calendar) date.clone()
    day.add(Calendar.DATE, offset)
    Calendar result = Calendar.getInstance(zone)
    result.clear()
    result.set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH), (int)(minutes / 60), minutes % 60, 0)
    result.timeInMillis
}
void refresh() {
    unschedule("refresh")
    Map c = config()
    if (!c) { emit("status", "configurationError"); return }
    long time = now()
    TimeZone zone = location.timeZone ?: TimeZone.getTimeZone("UTC")
    Calendar date = Calendar.getInstance(zone)
    date.timeInMillis = time
    boolean scheduledQuiet = false
    List<Long> upcoming = []
    (-1..7).each { int offset ->
        Calendar day = (Calendar) date.clone()
        day.add(Calendar.DATE, offset)
        int weekday = ((day.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
        if (c.days.contains(weekday.toString())) {
            long start = boundary(date, offset, c.start as int, zone)
            long end = boundary(date, offset + (c.end < c.start ? 1 : 0), c.end as int, zone)
            // A spring-forward normalization can collapse a very short interval.
            if (end > start) {
                if (time >= start && time < end) scheduledQuiet = true
                if (start > time) upcoming << start
                if (end > time) upcoming << end
            }
        }
    }
    boolean expired = state.overrideEnd != null && time >= (state.overrideEnd as Long)
    if (expired || !(state.overrideMode in ["quiet","normal"]) || state.overrideEnd == null) {
        state.overrideMode = null
        state.overrideEnd = null
    }
    boolean overridden = state.overrideEnd != null
    String mode = overridden ? state.overrideMode : (scheduledQuiet ? "quiet" : "normal")
    String previous = device.currentValue("quietState")
    emit("status", "ready")
    emit("quietState", mode)
    emit("switch", mode == "quiet" ? "on" : "off")
    emit("stateReason", overridden ? "override" : "schedule")
    emit("overrideState", overridden ? state.overrideMode : "none")
    emit("overrideUntil", overridden ? stamp(state.overrideEnd as Long, zone) : "none")
    emit("nextScheduleBoundary", upcoming ? stamp(upcoming.min(), zone) : "none")
    emit("scheduleZone", zone.ID)
    emit("lastSuccessfulUpdate", stamp(time, zone))
    if (previous != null && previous != mode) push(mode == "quiet" ? 1 : 2)
    if (expired) push(3)
    long next = Math.min(time + 900000L, upcoming ? upcoming.min() : time + 900000L)
    if (overridden) next = Math.min(next, state.overrideEnd as Long)
    runIn(Math.max(1L, Math.ceil((next - time) / 1000.0D).longValue()), "refresh")
    if (settings.logEnable) log.debug "Quiet Hours ${mode}; ${overridden ? 'override' : 'schedule'}"
}
void push(value) {
    try {
        BigDecimal button = new BigDecimal(value.toString())
        if (button < 1 || button > 3 || button.stripTrailingZeros().scale() > 0) return
        sendEvent(name:"pushed", value:button.intValue(), isStateChange:true, type:"digital")
    } catch (Exception ignored) { }
}
private String stamp(long time, TimeZone zone) { new Date(time).format("yyyy-MM-dd'T'HH:mm:ssXXX", zone) }
private void emit(String name, value) {
    if (device.currentValue(name)?.toString() != value?.toString()) sendEvent(name:name, value:value)
}
