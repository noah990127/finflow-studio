package com.finflow.studio.workflow;

import com.finflow.studio.workflow.WorkflowModels.ExecutionMode;
import com.finflow.studio.workflow.WorkflowModels.ScheduleDefinition;

import java.time.*;

final class WorkflowScheduleSupport {
    private WorkflowScheduleSupport() { }

    static Instant nextRun(ExecutionMode mode, ScheduleDefinition schedule, Instant after) {
        if (mode != ExecutionMode.SCHEDULED || schedule == null || schedule.frequency() == null) return null;
        var zone = zone(schedule.timezone());
        var cursor = after.atZone(zone);
        var time = parseTime(schedule.time());
        return switch (schedule.frequency()) {
            case HOURLY -> nextHourly(cursor, time).toInstant();
            case DAILY -> nextDaily(cursor, time).toInstant();
            case WEEKLY -> nextWeekly(cursor, time, clamp(schedule.dayOfWeek(), 1, 7, 1)).toInstant();
            case MONTHLY -> nextMonthly(cursor, time, clamp(schedule.dayOfMonth(), 1, 31, 1)).toInstant();
        };
    }

    static ZoneId zone(String value) {
        try { return ZoneId.of(value == null || value.isBlank() ? "Asia/Shanghai" : value); }
        catch (DateTimeException exception) { throw new IllegalArgumentException("请选择正确的执行时区"); }
    }

    static LocalTime parseTime(String value) {
        try { return LocalTime.parse(value == null || value.isBlank() ? "09:00" : value); }
        catch (DateTimeException exception) { throw new IllegalArgumentException("请选择正确的执行时间"); }
    }

    private static ZonedDateTime nextDaily(ZonedDateTime cursor, LocalTime time) {
        var candidate = cursor.toLocalDate().atTime(time).atZone(cursor.getZone());
        return candidate.isAfter(cursor) ? candidate : candidate.plusDays(1);
    }

    private static ZonedDateTime nextHourly(ZonedDateTime cursor, LocalTime time) {
        var candidate = cursor.withMinute(time.getMinute()).withSecond(0).withNano(0);
        return candidate.isAfter(cursor) ? candidate : candidate.plusHours(1);
    }

    private static ZonedDateTime nextWeekly(ZonedDateTime cursor, LocalTime time, int day) {
        var delta = Math.floorMod(day - cursor.getDayOfWeek().getValue(), 7);
        var candidate = cursor.toLocalDate().plusDays(delta).atTime(time).atZone(cursor.getZone());
        return candidate.isAfter(cursor) ? candidate : candidate.plusWeeks(1);
    }

    private static ZonedDateTime nextMonthly(ZonedDateTime cursor, LocalTime time, int day) {
        var date = cursor.toLocalDate();
        var candidateDate = date.withDayOfMonth(Math.min(day, date.lengthOfMonth()));
        var candidate = candidateDate.atTime(time).atZone(cursor.getZone());
        if (candidate.isAfter(cursor)) return candidate;
        var next = date.plusMonths(1);
        return next.withDayOfMonth(Math.min(day, next.lengthOfMonth())).atTime(time).atZone(cursor.getZone());
    }

    private static int clamp(Integer value, int min, int max, int fallback) {
        if (value == null) return fallback;
        return Math.max(min, Math.min(max, value));
    }
}
