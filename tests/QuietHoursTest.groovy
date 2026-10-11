import groovy.json.JsonOutput
import groovy.json.JsonSlurper
class HubDevice {
    Map values = [:], settings
    String displayName = 'Test appliance'
    Object currentValue(String key) { values[key] }
    void updateSetting(String key, Map setting) { settings[key] = setting.value == 'true' }
}
class Harness extends Script {
    Map state = [:], settings = [:], jobs = [:]
    List events = [], warnings = []
    HubDevice device = new HubDevice(settings: settings)
    Map location = [timeZone: TimeZone.getTimeZone('UTC')]
    long clock = 1000000L
    def log = [warn: { warnings << it }, debug: { }]
    Object run() { null }
    void metadata(Closure body) { }
    long now() { clock }
    void sendEvent(Map event) { events << event; device.values[event.name] = event.value }
    void runIn(Number seconds, String handler) { jobs[handler] = clock + seconds.longValue() * 1000L }
    void unschedule(String handler = null) { if (handler) jobs.remove(handler); else jobs.clear() }
    void restore(Map recovered) { state = recovered }
    void at(String instant) { clock = java.time.Instant.parse(instant).toEpochMilli() }
    void advance(long seconds) { clock += seconds * 1000L }
    int presses(int button) { events.count { it.name == 'pushed' && it.value == button } }
}
def compiler = new org.codehaus.groovy.control.CompilerConfiguration(scriptBaseClass: Harness.name)
def shell = new GroovyShell(this.class.classLoader, new Binding(), compiler)
def fresh = { Map prefs=[:], String instant='2026-10-09T21:59:00Z', String zone='UTC' ->
    def d=shell.parse(new File('QuietHours.groovy')); d.settings.putAll(prefs)
    d.location.timeZone=TimeZone.getTimeZone(zone); d.at(instant); d.run(); d.installed(); d
}
int groups=0
def check={ String label, Closure test -> test(); groups++; println "PASS ${label}" }
check('safe defaults and exact boundaries') {
    def d=fresh(); assert d.device.values.switch=='off'; assert d.jobs.refresh==d.now()+60000L
    d.advance(60); d.refresh(); assert d.device.values.switch=='on'; assert d.presses(1)==1
    d.at('2026-10-10T07:00:00Z'); d.refresh(); assert d.device.values.switch=='off'; assert d.presses(2)==1
}
check('overnight belongs to its start weekday') {
    def d=fresh([startDays:['5']], '2026-10-10T06:59:59Z'); assert d.device.values.switch=='on'
    d.advance(1); d.refresh(); assert d.device.values.switch=='off'
    d.at('2026-10-10T23:00:00Z'); d.refresh(); assert d.device.values.switch=='off'
    assert d.device.values.nextScheduleBoundary=='2026-10-16T22:00:00Z'
}
check('same-day period and Sunday selection') {
    def d=fresh([startTime:'09:00',endTime:'17:00',startDays:['7']], '2026-10-11T09:00:00Z')
    assert d.device.values.switch=='on'; d.at('2026-10-11T17:00:00Z'); d.refresh(); assert d.device.values.switch=='off'
}
check('midnight and year rollover') {
    def d=fresh([startTime:'00:00',endTime:'01:00'], '2027-01-01T00:00:00Z'); assert d.device.values.switch=='on'
    d.advance(3600); d.refresh(); assert d.device.values.switch=='off'
}
check('hub timezone controls wall-clock time') {
    def d=fresh([:], '2026-10-10T04:00:00Z','America/Denver'); assert d.device.values.switch=='on'
    assert d.device.values.scheduleZone=='America/Denver'
    assert d.device.values.nextScheduleBoundary=='2026-10-10T07:00:00-06:00'
}
check('manual quiet expiry resumes normal once') {
    def d=fresh(); d.forceQuiet(0.05); assert d.device.values.switch=='on'; assert d.jobs.refresh==d.now()+3000L
    d.advance(3); d.refresh(); assert d.device.values.switch=='off'; assert d.presses(3)==1
    d.refresh(); assert d.presses(3)==1
}
check('manual normal suppresses quiet and clearing restores it') {
    def d=fresh([:], '2026-10-09T23:00:00Z'); d.off(); assert d.device.values.switch=='off'
    d.clearOverride(); assert d.device.values.switch=='on'; assert d.presses(3)==0
}
check('repeated commands extend override without duplicate transition') {
    def d=fresh(); d.on(); d.advance(30); d.on(); assert d.state.overrideEnd==d.now()+3600000L
    assert d.presses(1)==1; d.refresh(); assert d.presses(1)==1
}
check('invalid duration has no effect') {
    def d=fresh(); def before=d.state.clone()
    [-1,0,0.001,1441,'NaN','Infinity','bad'].each { d.forceQuiet(it) }; assert d.state==before
    assert d.warnings.size()==7
}
check('invalid configuration retains state and recovers') {
    def d=fresh(); d.on(); d.settings.endTime='22:00'; d.updated()
    assert d.device.values.status=='configurationError'; assert d.device.values.switch=='on'; assert !d.jobs.refresh
    d.off(); assert d.device.values.switch=='on'; d.settings.endTime='07:00'; d.updated()
    assert d.device.values.status=='ready'; assert d.device.values.switch=='on'
    ['24:00','9:00','garbage',''].each { d.settings.startTime=it; d.updated(); assert d.device.values.status=='configurationError' }
}
check('invalid days and default duration rejected') {
    assert fresh([startDays:' 5 , 6 '], '2026-10-10T06:00:00Z').device.values.switch=='on'
    ['', '1,', '1,,2'].each { assert fresh([startDays:it]).device.values.status=='configurationError' }
    [[],['8'],['Monday']].each { assert fresh([startDays:it]).device.values.status=='configurationError' }
    assert fresh([overrideMinutes:'NaN']).device.values.status=='configurationError'
}
check('JSON restart catches expired override and stale callback is harmless') {
    def d=fresh(); d.on(); d.restore(new JsonSlurper().parseText(JsonOutput.toJson(d.state)))
    d.initialize(); assert d.device.values.switch=='on'; d.advance(3600); d.initialize()
    assert d.device.values.overrideState=='none'; assert d.presses(3)==1
    d.forceQuiet(1); d.clearOverride(); d.advance(60); d.refresh(); assert d.device.values.overrideState=='none'
}
check('DST spring gap normalizes forward and collapsed interval is skipped') {
    def d=fresh([startTime:'02:30',endTime:'04:00'], '2026-03-08T07:29:59Z','America/New_York')
    assert d.device.values.switch=='off'; d.advance(1); d.refresh(); assert d.device.values.switch=='on'
    def e=fresh([startTime:'02:30',endTime:'03:00'], '2026-03-08T07:45:00Z','America/New_York')
    assert e.device.values.switch=='off'
}
check('DST repeated hour uses later occurrence') {
    def d=fresh([startTime:'01:30',endTime:'02:00'], '2026-11-01T05:45:00Z','America/New_York')
    assert d.device.values.switch=='off'; d.at('2026-11-01T06:30:00Z'); d.refresh(); assert d.device.values.switch=='on'
    d.at('2026-11-01T07:00:00Z'); d.refresh(); assert d.device.values.switch=='off'
}
check('buttons, periodic check, debug and removal') {
    def d=fresh([logEnable:true], '2026-10-09T12:00:00Z'); assert d.jobs.refresh==d.now()+900000L
    [0,4,1.5,'NaN'].each { d.push(it) }; assert d.presses(1)==0
    d.push(1); d.push(1); assert d.presses(1)==2; assert d.device.values.switch=='off'
    assert d.jobs.logsOff; d.logsOff(); assert !d.settings.logEnable; d.uninstalled(); assert !d.jobs
}
println "${groups} behavior groups passed"
