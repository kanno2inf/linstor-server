package com.linbit.linstor.backupshipping;

import com.linbit.ImplementationError;
import com.linbit.SystemServiceStartException;
import com.linbit.extproc.DaemonHandler;
import com.linbit.extproc.OutputProxy.EOFEvent;
import com.linbit.extproc.OutputProxy.Event;
import com.linbit.extproc.OutputProxy.ExceptionEvent;
import com.linbit.extproc.OutputProxy.StdErrEvent;
import com.linbit.extproc.OutputProxy.StdOutEvent;
import com.linbit.linstor.api.BackupToS3;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.storage.StorageException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.Consumer;

import com.amazonaws.SdkClientException;

public class BackupShippingDaemon implements Runnable
{
    private static final int DFLT_DEQUE_CAPACITY = 100;
    private final ErrorReporter errorReporter;
    private final Thread cmdThread;
    private final Thread s3Thread;
    private final String[] command;
    private final BackupToS3 backupHandler;
    private final String backupName;
    private final String bucketName;
    private final long volSize;

    private final LinkedBlockingDeque<Event> deque;
    private final DaemonHandler handler;
    private final Object syncObj = new Object();

    private boolean started = false;
    private Process cmdProcess;
    private boolean s3Finished = false;
    private boolean cmdFinished = false;
    private boolean success = true;

    private final Consumer<Boolean> afterTermination;

    public BackupShippingDaemon(
        ErrorReporter errorReporterRef,
        ThreadGroup threadGroupRef,
        String threadName,
        String[] commandRef,
        String backupNameRef,
        String bucketNameRef,
        BackupToS3 backupHandlerRef,
        boolean restore,
        long size,
        Consumer<Boolean> afterTerminationRef
    )
    {
        errorReporter = errorReporterRef;
        command = commandRef;
        bucketName = bucketNameRef;
        afterTermination = afterTerminationRef;
        backupName = backupNameRef;
        backupHandler = backupHandlerRef;
        volSize = size;

        deque = new LinkedBlockingDeque<>(DFLT_DEQUE_CAPACITY);
        handler = new DaemonHandler(deque, command);

        cmdThread = new Thread(threadGroupRef, this, threadName);

        if (restore)
        {
            s3Thread = new Thread(threadGroupRef, this::runRestoring, "backup_" + threadName);
        }
        else
        {
            handler.setStdOutListener(false);
            s3Thread = new Thread(threadGroupRef, this::runShipping, "backup_" + threadName);
        }
    }

    public void start()
    {
        started = true;
        cmdThread.start();
        try
        {
            cmdProcess = handler.start();
            s3Thread.start();
        }
        catch (IOException exc)
        {
            errorReporter.reportError(
                new SystemServiceStartException(
                    "Unable to daemon for SnapshotShipping",
                    "I/O error attempting to start '" + Arrays.toString(command) + "'",
                    exc.getMessage(),
                    null,
                    null,
                    exc,
                    false
                )
            );
        }
    }

    private void runShipping()
    {
        try
        {
            backupHandler.putObjectMultipart(backupName, cmdProcess.getInputStream(), volSize);
            synchronized (syncObj)
            {
                s3Finished = true;
                // success is true, no need to set again
                if (cmdFinished && s3Finished)
                {
                    afterTermination.accept(success);
                }
            }
        }
        catch (SdkClientException | IOException | StorageException exc)
        {
            synchronized (syncObj)
            {
                if (!s3Finished)
                {
                    success = false;
                    s3Finished = true;
                    // closing the stream might throw exceptions, which do not need to be reported if we already
                    // finished previously
                    errorReporter.reportError(exc);
                }
                if (cmdFinished && s3Finished)
                {
                    afterTermination.accept(success);
                }
            }
        }
    }

    private void runRestoring()
    {
        try (
            InputStream is = backupHandler.getObject(backupName, bucketName);
            OutputStream os = cmdProcess.getOutputStream();
        )
        {
            byte[] read_buf = new byte[1 << 20];
            int read_len = 0;
            while ((read_len = is.read(read_buf)) != -1)
            {
                os.write(read_buf, 0, read_len);
            }
            os.flush();
            Thread.sleep(500);
            synchronized (syncObj)
            {
                s3Finished = true;
                // success is true, no need to set again
                if (cmdFinished && s3Finished)
                {
                    afterTermination.accept(success);
                }
            }
        }
        catch (SdkClientException | IOException exc)
        {
            synchronized (syncObj)
            {
                if (!s3Finished)
                {
                    success = false;
                    s3Finished = true;
                    // closing the streams might throw exceptions, which do not need to be reported if we already
                    // finished previously
                    errorReporter.reportError(exc);
                }
                if (cmdFinished && s3Finished)
                {
                    afterTermination.accept(success);
                }
            }
        }
        catch (InterruptedException ignored)
        {
        }
    }

    @Override
    public void run()
    {
        errorReporter.logTrace("starting daemon: %s", Arrays.toString(command));
        while (started)
        {
            Event event;
            try
            {
                event = deque.take();
                if (event instanceof StdOutEvent)
                {
                    errorReporter.logTrace("stdOut: %s", new String(((StdOutEvent) event).data));
                    // should not exist when sending due to handler.setStdOutListener(false)
                }
                else if (event instanceof StdErrEvent)
                {
                    String stdErr = new String(((StdErrEvent) event).data);
                    errorReporter.logWarning("stdErr: %s", stdErr);
                }
                else if (event instanceof ExceptionEvent)
                {
                    errorReporter.logTrace("ExceptionEvent in '%s':", Arrays.toString(command));
                    errorReporter.reportError(((ExceptionEvent) event).exc);
                    // FIXME: Report the exception to the controller
                }
                else if (event instanceof PoisonEvent)
                {
                    errorReporter.logTrace("PoisonEvent");
                    break;
                }
                else if (event instanceof EOFEvent)
                {
                    int exitCode = handler.getExitCode();
                    errorReporter.logTrace("EOF. Exit code: " + exitCode);
                    synchronized (syncObj)
                    {
                        if (!cmdFinished)
                        {
                            cmdFinished = true;
                            success &= exitCode == 0;
                            if (s3Finished)
                            {
                                afterTermination.accept(success);
                            }
                        }
                    }
                }
            }
            catch (InterruptedException exc)
            {
                if (started)
                {
                    errorReporter.reportError(new ImplementationError(exc));
                }
            }
            catch (Exception exc)
            {
                errorReporter.reportError(
                    new ImplementationError(
                        "Unknown exception occurred while executing '" + Arrays.toString(command) + "'",
                        exc
                    )
                );
            }
        }
    }

    /**
     * Simple event forcing this thread to kill itself
     */
    private static class PoisonEvent implements Event
    {
    }

    public void shutdown()
    {
        started = false;
        handler.stop(false);
        if (cmdThread != null)
        {
            cmdThread.interrupt();
        }
        if (s3Thread != null)
        {
            s3Thread.interrupt();
        }
        deque.addFirst(new PoisonEvent());
    }

    public void awaitShutdown(long timeoutRef) throws InterruptedException
    {
        if (cmdThread != null)
        {
            cmdThread.join(timeoutRef);
        }
        if (s3Thread != null)
        {
            s3Thread.join(timeoutRef);
        }
    }
}
