# Quiet Hours

A local Hubitat virtual switch for the household's quiet period. Use it to suppress spoken announcements, dim motion lighting, or defer nonessential notifications. Weekday schedules can cross midnight; timed overrides let someone sleep in or temporarily resume normal activity.

## Requirements and privacy

Install on a Hubitat Elevation hub with the correct clock and time zone. No app, subscription, API key, network polling, or external library is required. All calculations and saved override state stay on the hub. The optional source import contacts GitHub only when you request an update. MIT licensed.

## Installation and updates

1. In **Drivers Code**, add a driver and paste [QuietHours.groovy](https://raw.githubusercontent.com/b69ca/hubitat-quiethours/main/QuietHours.groovy), then Save.
2. Add a virtual device with type **Quiet Hours**.
3. Save Preferences and press Initialize. Add the switch to your dashboard or rules.

For updates, replace the driver source or use its Import URL, Save, then Initialize each device. Overrides remain saved. Removing the device cancels its schedules; remove dependent rules first. Removing just the driver does not remove devices.

## Configuration

| Preference | Recommended start | Meaning |
|---|---|---|
| Quiet time starts | `22:00` | Exactly two-digit, 24-hour `HH:mm` |
| Quiet time ends | `07:00` | Earlier end time means the following day |
| Start weekdays | `1,2,3,4,5,6,7` | Comma-separated start days: 1 Monday through 7 Sunday |
| Default manual override | 60 minutes | Used by On, Off, and blank custom-command durations |
| Debug logging | Off | Automatically turns off after 30 minutes |

Use `1,2,3,4,5` for weekday starts, `6,7` for weekend starts, or `5` for Friday only. Friday 22:00–07:00 includes Saturday morning even when Saturday is unselected. The start boundary is included and the end boundary is excluded. Equal start and end times are invalid; a full-day interval is not supported. There is one interval per selected day.

The hub time zone controls wall-clock schedules. An override is elapsed time, independent of daylight-saving changes. In a spring clock gap, Java Calendar moves a nonexistent boundary forward by the gap; an interval whose end then precedes its start is skipped that day. In a repeated fall hour, the boundary uses the later occurrence. Avoid transition-hour boundaries if these semantics do not match your household routine.

## Commands and capabilities

Capabilities: Actuator, Sensor, Switch, Refresh, Initialize, PushableButton.

| Command | Result |
|---|---|
| `on()` / `forceQuiet(minutes)` | Quiet override; switch On |
| `off()` / `forceNormal(minutes)` | Normal override; switch Off |
| `clearOverride()` | Resume the current schedule immediately |
| `refresh()` | Recalculate now and restore the next timer |
| `initialize()` | Rebuild schedules while retaining an override |
| `push(number)` | Test a button event without changing quiet state |

Duration accepts 0.017–1440 minutes, including decimals; blank uses the preference. Repeating On or Off restarts the override duration. An expired override resumes whichever scheduled state applies at that moment. On and Off always create overrides, including when the switch already has that value.

| Attribute | Values / purpose |
|---|---|
| `switch` | `on` means quiet; `off` means normal |
| `quietState` | `quiet`, `normal` |
| `status` | `ready`, `configurationError` |
| `stateReason` | `schedule`, `override` |
| `overrideState` | `none`, `quiet`, `normal` |
| `overrideUntil` | Timestamp with offset, or `none` |
| `nextScheduleBoundary` | Next scheduled start or end, even during an override |
| `scheduleZone` | Hub time-zone identifier; UTC if unavailable |
| `lastSuccessfulUpdate` | Last successful calculation timestamp |
| `numberOfButtons` / `pushed` | Three buttons / latest trigger |

Button 1: entered quiet. Button 2: returned to normal. Button 3: override expired. No transition button is sent for initial installation. Expiry may send both a transition and button 3; choose one trigger when one notification is desired. Repeated refreshes suppress duplicate state events. Test pushes intentionally repeat.

## Automation examples

- **Quiet motion lights:** In a motion-light rule, use this switch as a condition: On selects a low brightness; Off selects normal brightness.
- **Announcement gate:** Before a spoken doorbell or appliance announcement, require Quiet Hours to be Off. Route important alerts through your existing priority rules.
- **Sleep in:** A dashboard On button starts a 60-minute quiet override. A custom Rule Machine action `forceQuiet(120)` extends quiet time by two hours.
- **Guests awake late:** Call `forceNormal(90)` to temporarily allow normal lighting and announcements. Call `clearOverride()` to return to the schedule early.

This driver reports a state. It does not directly control speakers, lights, notifications, or hub modes; connect those through rules.

## Scheduling and failure behavior

The driver schedules the next boundary or override expiry and rechecks at least every 15 minutes for clock/time-zone changes. Hub load can delay callbacks; it calculates the current state rather than replaying missed transitions. Initialize after reboot restores scheduling and catches expired overrides. No external data is involved, so a stale-data attribute is unnecessary.

Invalid preferences set `configurationError`, cancel the refresh schedule, and retain the last known switch state. Manual On/Off are blocked until corrected. Clear Override still clears saved override state, but cannot calculate a new switch value until preferences are valid. Save valid preferences to recover. Invalid command durations are ignored with a short warning.

If behavior seems wrong, check `status`, selected start weekdays, 24-hour text formatting, the hub clock/time zone, and `overrideState`. Clear Override and Refresh distinguish a deliberate override from a schedule problem. Check Scheduled Jobs for `refresh`; enable debug temporarily if needed.

## Validation

The Groovy 2.4 behavior suite covers weekday ownership, exact boundaries, time zones, calendar rollover, DST gaps/repeated hours, overrides, restart recovery, invalid settings, duplicate suppression, scheduling, and cleanup. Run with Java 17 and Groovy 2.4.21:

```sh
java -cp groovy.jar groovy.ui.GroovyMain tests/QuietHoursTest.groovy
```

GitHub Actions runs the same suite. Standalone tests complement real Hubitat compilation and command/schedule validation; they do not emulate the entire platform.

Platform references: [Hubitat capabilities](https://docs2.hubitat.com/en/developer/driver/capability-list), [common methods](https://docs2.hubitat.com/en/developer/common-methods-object), [Java Calendar](https://docs.oracle.com/javase/8/docs/api/java/util/Calendar.html).
