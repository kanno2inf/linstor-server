package com.linbit.linstor.tasks;

import com.linbit.linstor.core.objects.Schedule;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.security.TestAccessContextProvider;
import com.linbit.utils.Pair;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
@RunWith(JUnitParamsRunner.class)
public class CronTests
{
    private static final DateFormat SDF = new SimpleDateFormat("yyyy.MM.dd HH:mm:ss");
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss");
    private static final CronDefinition CRON_DFN = CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX);
    private static final CronParser CRON_PARSER = new CronParser(CRON_DFN);
    /** every 24h (0:00) */
    private static final String DFLT_FULL_EXEC = "0 0 * * *";
    /** at 8:00 and 16:00 */
    private static final String DFLT_INCR_EXEC = "0 8,16 * * *";
    private static final long DUMMY_DFLT_RETRY_DELAY_IN_MS = 60_000;

    @Test
    @Parameters(method = "testData")
    public void testConstruction(Input input) throws ParseException, AccessDeniedException
    {
        Schedule schedule = Mockito.mock(Schedule.class);
        Mockito.when(schedule.getFullCron(Mockito.any())).thenReturn(CRON_PARSER.parse(input.fullExec));
        Mockito.when(schedule.getIncCron(Mockito.any())).thenReturn(CRON_PARSER.parse(input.incExec));
        Pair<Long, Boolean> res = ScheduleBackupService.getTimeoutAndType(
            schedule,
            TestAccessContextProvider.PUBLIC_CTX,
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(input.now), ZoneId.systemDefault()),
            input.lastStart,
            input.lastBackupSuccess,
            input.skip,
            input.prevIncr
        );
        assertEquals(
            "expected: " + SDF.format(new Date(input.expectedResult.timeout)) + ", actual: " +
                SDF.format(new Date(res.objA)),
            new Long(input.expectedResult.timeout),
            res.objA
        );
        assertEquals(input.expectedResult.incr, res.objB);
    }

    @SuppressWarnings("unused")
    private List<Input> testData() throws ParseException
    {
        return Arrays.asList(
            new InputSuccess("2022.05.12 00:00:00", "2022.05.12 02:00:00", "2022.05.12 08:00:00", true),
            new InputSuccess("2022.05.12 00:00:02", "2022.05.12 00:00:02", "2022.05.12 08:00:00", true),

            new InputSuccess("2022.05.12 00:00:02", "2022.05.12 02:00:00", "2022.05.12 08:00:00", true),
            new InputSuccess("2022.05.12 00:00:02", "2022.05.12 08:01:00", "2022.05.12 08:01:00", true),
            new InputSuccess("2022.05.12 00:00:02", "2022.05.12 15:59:00", "2022.05.12 15:59:00", true),
            new InputSuccess("2022.05.12 00:00:02", "2022.05.12 16:01:00", "2022.05.12 16:01:00", true),
            new InputSuccess("2022.05.12 00:00:02", "2022.05.13 01:00:00", "2022.05.13 01:00:00", false),

            new InputSuccess("2022.05.12 08:00:02", "2022.05.12 09:00:00", "2022.05.12 16:00:00", true),
            new InputSuccess("2022.05.12 08:00:02", "2022.05.12 16:01:00", "2022.05.12 16:01:00", true),
            new InputSuccess("2022.05.12 08:00:02", "2022.05.13 01:00:00", "2022.05.13 01:00:00", false),

            new InputSuccess(
                "0 */2 * * *",
                "0 */1 * * *",
                "2022.05.12 07:00:02",
                "2022.05.12 07:30:00",
                "2022.05.12 08:00:00",
                false
            ),
            new InputSuccess(
                "0 */2 * * *",
                "0 */1 * * *",
                "2022.05.12 08:00:02",
                "2022.05.12 08:30:00",
                "2022.05.12 09:00:00",
                true
            ),

            new InputFailure("2022.05.12 00:00:02", false, "2022.05.12 02:00:00", false, "2022.05.12 02:01:00", false),
            new InputFailure("2022.05.12 00:00:02", false, "2022.05.12 08:01:00", false, "2022.05.12 08:02:00", false),
            new InputFailure("2022.05.12 00:00:02", false, "2022.05.13 01:00:00", false, "2022.05.13 01:01:00", false),

            new InputFailure("2022.05.12 08:00:02", true, "2022.05.12 08:10:00", false, "2022.05.12 08:11:00", true),
            new InputFailure("2022.05.12 16:00:02", false, "2022.05.13 01:00:00", false, "2022.05.13 01:01:00", false),

            new InputFailure("2022.05.12 00:00:02", false, "2022.05.13 01:00:00", true, "2022.05.13 08:00:00", true),
            new InputFailure("2022.05.12 00:00:02", false, "2022.05.13 09:00:00", true, "2022.05.13 16:00:00", true),
            new InputFailure("2022.05.12 00:00:02", false, "2022.05.13 23:00:00", true, "2022.05.14 00:00:00", false),
            new InputFailure("2022.05.12 00:00:02", true, "2022.05.13 23:00:00", true, "2022.05.14 00:00:00", false),

            new InputFailure("2022.05.12 00:00:02", false, "2022.05.12 08:10:00", false, "2022.05.12 08:11:00", false)
        );
    }

    private static abstract class Input
    {
        String fullExec;
        String incExec;
        long lastStart;
        boolean prevIncr;
        boolean lastBackupSuccess;
        boolean skip;
        long now;
        Result expectedResult;

        Input(
            String lastStartStr,
            String nowStr,
            boolean prevIncrRef,
            String expectedRerun,
            boolean expectedIncr,
            boolean lastBackupSuccessRef,
            boolean skipRef
        ) throws ParseException
        {
            this(
                DFLT_FULL_EXEC,
                DFLT_INCR_EXEC,
                lastStartStr,
                nowStr,
                prevIncrRef,
                expectedRerun,
                expectedIncr,
                lastBackupSuccessRef,
                skipRef
            );
        }

        Input(
            String fullExecRef,
            String incExecRef,
            String lastStartStr,
            String nowStr,
            boolean prevIncrRef,
            String expectedRerun,
            boolean expectedIncr,
            boolean lastBackupSuccessRef,
            boolean skipRef
        )
            throws ParseException
        {
            fullExec = fullExecRef;
            incExec = incExecRef;
            lastStart = lastStartStr == null || lastStartStr.equals("-1") ? -1 : SDF.parse(lastStartStr).getTime();
            prevIncr = prevIncrRef;
            now = SDF.parse(nowStr).getTime();
            expectedResult = new Result(
                SDF.parse(expectedRerun).getTime() - SDF.parse(nowStr).getTime(),
                expectedIncr
            );
            lastBackupSuccess = lastBackupSuccessRef;
            skip = skipRef;
        }

        @Override
        public String toString()
        {
            return SDF.format(new Date(lastStart)) + ", " + SDF.format(new Date(now));
        }
    }

    private static class InputSuccess extends Input
    {
        InputSuccess(String lastStart, String now, String expeRerun, boolean expIncr)
            throws ParseException
        {
            super(lastStart, now, false, expeRerun, expIncr, true, false);
        }

        InputSuccess(
            String fullExecRef,
            String incExecRef,
            String lastStart,
            String now,
            String expeRerun,
            boolean expIncr
        )
            throws ParseException
        {
            super(fullExecRef, incExecRef, lastStart, now, false, expeRerun, expIncr, true, false);
        }
    }

    private static class InputFailure extends Input
    {
        InputFailure(
            String lastStart,
            boolean prevIncr,
            String now,
            boolean skip,
            String expRerun,
            boolean expIncr
        )
            throws ParseException
        {
            super(lastStart, now, prevIncr, expRerun, expIncr, false, skip);
        }
    }

    private static class Result
    {
        long timeout;
        boolean incr;
        Result(long timeoutRef, boolean incrRef)
        {
            timeout = timeoutRef;
            incr = incrRef;
        }
    }
}
