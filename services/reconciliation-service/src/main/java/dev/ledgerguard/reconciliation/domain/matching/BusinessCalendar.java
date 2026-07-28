package dev.ledgerguard.reconciliation.domain.matching;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * Business-day arithmetic for settlement windows.
 *
 * <p>DATE_TOLERANCE_MATCH counts <b>business</b> days, not calendar days. A two-day settlement
 * window opened on a Friday closes on the following Tuesday — four calendar days later. Using
 * {@code plusDays(2)} instead produces a break for every Friday payment, every week, and the
 * resulting noise is exactly the kind that erodes trust in a reconciliation system.
 *
 * <p><b>Stated limitation:</b> weekends only. Public holidays are not modelled, so a payment
 * settling across a bank holiday can still raise a spurious date break. A real deployment needs a
 * per-currency holiday calendar; that is genuine work with a genuine data dependency, and pretending
 * otherwise would be worse than saying so. The {@code holidays} parameter exists so a calendar can
 * be supplied without changing any call site.
 */
public final class BusinessCalendar {

    private final Set<LocalDate> holidays;

    /** Weekends only. */
    public static BusinessCalendar weekendsOnly() {
        return new BusinessCalendar(Set.of());
    }

    public BusinessCalendar(Set<LocalDate> holidays) {
        this.holidays = Set.copyOf(holidays);
    }

    public boolean isBusinessDay(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY
                && date.getDayOfWeek() != DayOfWeek.SUNDAY
                && !holidays.contains(date);
    }

    /**
     * Business days between two dates, order-independent.
     *
     * <p>Counts days moved, not days spanned: Friday to Monday is 1 business day.
     */
    public int businessDaysBetween(LocalDate from, LocalDate to) {
        LocalDate earlier = from.isBefore(to) ? from : to;
        LocalDate later = from.isBefore(to) ? to : from;

        int count = 0;
        LocalDate cursor = earlier;
        while (cursor.isBefore(later)) {
            cursor = cursor.plusDays(1);
            if (isBusinessDay(cursor)) {
                count++;
            }
        }
        return count;
    }
}
