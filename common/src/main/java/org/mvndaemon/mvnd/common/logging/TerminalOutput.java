/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.mvndaemon.mvnd.common.logging;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.impl.AbstractPosixTerminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Display;
import org.mvndaemon.mvnd.common.Message;
import org.mvndaemon.mvnd.common.Message.BuildException;
import org.mvndaemon.mvnd.common.Message.BuildStarted;
import org.mvndaemon.mvnd.common.Message.ExecutionFailureEvent;
import org.mvndaemon.mvnd.common.Message.MojoStartedEvent;
import org.mvndaemon.mvnd.common.Message.ProjectEvent;
import org.mvndaemon.mvnd.common.Message.RequestInput;
import org.mvndaemon.mvnd.common.Message.StringMessage;
import org.mvndaemon.mvnd.common.Message.TransferEvent;
import org.mvndaemon.mvnd.common.OsUtils;

/**
 * A terminal {@link ClientOutput} based on JLine.
 */
public class TerminalOutput implements ClientOutput {

    /**
     * The '+' key is used to increase the number of lines displayed per project.
     */
    public static final int KEY_PLUS = '+';
    /**
     * The '-' key is used to decrease the number of lines displayed per project.
     */
    public static final int KEY_MINUS = '-';
    /**
     * The Ctrl+B key switches between the no-buffering / buffering modes.
     * In no-buffering mode, the output of concurrent builds will be interleaved and
     * each line will be prepended with the module name in order to distinguish them.
     * In buffering mode, the list of modules being built is displayed and update
     * continuously. In this mode, pressing '+' one or more times will open a rolling
     * window for each module with the related output.
     */
    public static final int KEY_CTRL_B = 'B' & 0x1f;
    /**
     * The Ctrl+L key forces a redisplay of the output.
     */
    public static final int KEY_CTRL_L = 'L' & 0x1f;
    /**
     * The Ctrl+M (or enter) switches between full-buffering and module-buffering.
     * In the full-buffering mode, all the build output is buffered and displayed
     * at the end of the build, while the module-buffering mode will output in
     * the terminal the log for a given module once it's finished.
     */
    public static final int KEY_CTRL_M = 'M' & 0x1f;

    private static final AttributedStyle GREEN_FOREGROUND = new AttributedStyle().foreground(AttributedStyle.GREEN);
    private static final AttributedStyle CYAN_FOREGROUND = new AttributedStyle().foreground(AttributedStyle.CYAN);

    private final Terminal terminal;
    private final Terminal.SignalHandler previousIntHandler;
    private final Display display;
    private final Map<String, Map<String, TransferEvent>> transfers = new LinkedHashMap<>();
    private final ArrayList<ExecutionFailureEvent> failures = new ArrayList<>();
    private final LinkedHashMap<String, Project> projects = new LinkedHashMap<>();
    private final ClientLog log;
    private volatile Exception exception;
    private volatile boolean closing;
    private final long start;
    private final boolean dumb;
    private final TerminalInputHandler inputHandler;

    /** A sink for sending messages back to the daemon */
    private volatile Consumer<Message> daemonDispatch;
    /** A sink for queuing messages to the main queue */
    private volatile Consumer<Message> daemonReceive;

    /*
     * The following non-final fields are read/written from the main thread only.
     * This is guaranteed as follows:
     * * The read/write ops are only reachable from accept(Message) and accept(List<Message>)
     * * Both of these methods are guarded with "main".equals(Thread.currentThread().getName()) assertion
     * Therefore, these fields do not need to be volatile
     */
    private String name;
    private String daemonId;
    private int totalProjects;
    /** String format for formatting the number of projects done with padding based on {@link #totalProjects} */
    private String projectsDoneFomat;

    private int maxThreads;
    private String artifactIdFormat;
    /** String format for formatting the actual/hidden/max thread counts */
    private String threadsFormat;

    private int linesPerProject = 0;
    private int doneProjects = 0;
    private String buildStatus;
    private boolean displayDone = false;
    private boolean noBuffering;

    /**
     * {@link Project} is owned by the display loop thread and is accessed only from there. Therefore it does not need
     * to be immutable.
     */
    static class Project {
        final String id;
        MojoStartedEvent runningExecution;
        final List<String> log = new ArrayList<>();

        public Project(String id) {
            this.id = id;
        }
    }

    public TerminalOutput(boolean noBuffering, int rollingWindowSize, Path logFile) throws IOException {
        this.start = System.currentTimeMillis();
        TerminalBuilder builder = TerminalBuilder.builder();
        builder.systemOutput(TerminalBuilder.SystemOutput.SysErr);
        this.terminal = builder.build();
        this.dumb = terminal.getType().startsWith("dumb");
        this.noBuffering = noBuffering;
        this.linesPerProject = rollingWindowSize;
        terminal.enterRawMode();
        Thread mainThread = Thread.currentThread();
        daemonDispatch = m -> {
            if (m == Message.BareMessage.CANCEL_BUILD_SINGLETON) {
                mainThread.interrupt();
            }
        };
        this.previousIntHandler = terminal.handle(
                Terminal.Signal.INT, sig -> daemonDispatch.accept(Message.BareMessage.CANCEL_BUILD_SINGLETON));
        this.display = new Display(terminal, false);
        this.log = logFile == null ? new MessageCollector() : new FileLog(logFile);
        this.inputHandler = new TerminalInputHandler(terminal, this.dumb);
    }

    @Override
    public void setDaemonId(String daemonId) {
        this.daemonId = daemonId;
    }

    @Override
    public void setDaemonDispatch(Consumer<Message> daemonDispatch) {
        this.daemonDispatch = daemonDispatch;
        this.inputHandler.setDaemonDispatch(daemonDispatch);
    }

    @Override
    public void setDaemonReceive(Consumer<Message> daemonReceive) {
        this.daemonReceive = daemonReceive;
        this.inputHandler.setDaemonReceive(daemonReceive);
    }

    @Override
    public void accept(Message entry) {
        assert "main".equals(Thread.currentThread().getName());
        if (doAccept(entry)) {
            update();
        }
    }

    @Override
    public void accept(List<Message> entries) {
        assert "main".equals(Thread.currentThread().getName());
        for (Message entry : entries) {
            if (!doAccept(entry)) {
                return;
            }
        }
        update();
    }

    private boolean doAccept(Message entry) {
        switch (entry.getType()) {
            case Message.BUILD_STARTED: {
                BuildStarted bs = (BuildStarted) entry;
                this.name = bs.getProjectId();
                this.totalProjects = bs.getProjectCount();
                final int totalProjectsDigits = (int) (Math.log10(totalProjects) + 1);
                this.projectsDoneFomat = "%" + totalProjectsDigits + "d";
                this.maxThreads = bs.getMaxThreads();
                this.inputHandler.setMaxThreads(maxThreads);
                this.artifactIdFormat = "%-" + bs.getArtifactIdDisplayLength() + "s ";
                final int maxThreadsDigits = (int) (Math.log10(maxThreads) + 1);
                this.threadsFormat = "%" + (maxThreadsDigits * 3 + 2) + "s";
                if (maxThreads <= 1 || totalProjects <= 1) {
                    this.noBuffering = true;
                    display.update(Collections.emptyList(), 0);
                    applyNoBuffering();
                }
                break;
            }
            case Message.CANCEL_BUILD: {
                projects.values().stream().flatMap(p -> p.log.stream()).forEach(log);
                clearDisplay();
                try {
                    log.close();
                } catch (IOException e1) {
                    throw new RuntimeException(e1);
                }
                final AttributedStyle s = new AttributedStyle().bold().foreground(AttributedStyle.RED);
                new AttributedString("The build was canceled", s).println(terminal);
                terminal.flush();
                return false;
            }
            case Message.BUILD_EXCEPTION: {
                final BuildException e = (BuildException) entry;
                final String msg;
                if ("org.apache.commons.cli.UnrecognizedOptionException".equals(e.getClassName())) {
                    msg = "Unable to parse command line options: " + e.getMessage();
                } else {
                    msg = e.getClassName() + ": " + e.getMessage();
                }
                projects.values().stream().flatMap(p -> p.log.stream()).forEach(log);
                clearDisplay();
                try {
                    log.close();
                } catch (IOException e1) {
                    throw new RuntimeException(e1);
                }
                final AttributedStyle s = new AttributedStyle().bold().foreground(AttributedStyle.RED);
                new AttributedString(msg, s).println(terminal);
                terminal.flush();
                return false;
            }
            case Message.PROJECT_STARTED: {
                StringMessage be = (StringMessage) entry;
                final String artifactId = be.getMessage();
                projects.put(artifactId, new Project(artifactId));
                break;
            }
            case Message.MOJO_STARTED: {
                final MojoStartedEvent execution = (MojoStartedEvent) entry;
                final Project prj = projects.computeIfAbsent(execution.getArtifactId(), Project::new);
                prj.runningExecution = execution;
                break;
            }
            case Message.PROJECT_STOPPED: {
                StringMessage be = (StringMessage) entry;
                final String artifactId = be.getMessage();
                Project prj = projects.remove(artifactId);
                if (prj != null) {
                    prj.log.forEach(log);
                }
                doneProjects++;
                displayDone();
                break;
            }
            case Message.BUILD_STATUS: {
                this.buildStatus = ((StringMessage) entry).getMessage();
                break;
            }
            case Message.BUILD_FINISHED: {
                projects.values().stream().flatMap(p -> p.log.stream()).forEach(log);
                clearDisplay();
                try {
                    log.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                } finally {
                    terminal.flush();
                }
                return false;
            }
            case Message.KEEP_ALIVE: {
                break;
            }
            case Message.DISPLAY: {
                clearDisplay();
                Message.ProjectEvent d = (Message.ProjectEvent) entry;
                terminal.writer().printf("[%s] %s%n", d.getProjectId(), d.getMessage());
                break;
            }
            case Message.PRINT_OUT: {
                Message.StringMessage d = (Message.StringMessage) entry;
                if (log instanceof FileLog) {
                    log.accept(d.getMessage());
                } else {
                    clearDisplay();
                    System.out.printf("%s%n", d.getMessage());
                }
                break;
            }
            case Message.PRINT_ERR: {
                Message.StringMessage d = (Message.StringMessage) entry;
                if (log instanceof FileLog) {
                    log.accept(d.getMessage());
                } else {
                    clearDisplay();
                    System.err.printf("%s%n", d.getMessage());
                }
                break;
            }
            case Message.PROMPT: {
                Message.Prompt prompt = (Message.Prompt) entry;
                if (dumb) {
                    terminal.writer().println("");
                    break;
                }
                clearDisplay();
                inputHandler.requestPrompt(prompt);
                break;
            }
            case Message.BUILD_LOG_MESSAGE: {
                StringMessage sm = (StringMessage) entry;
                log.accept(sm.getMessage());
                break;
            }
            case Message.PROJECT_LOG_MESSAGE: {
                final ProjectEvent bm = (ProjectEvent) entry;
                final Project prj = projects.get(bm.getProjectId());
                if (prj == null) {
                    log.accept(bm.getMessage());
                } else if (noBuffering || dumb) {
                    String msg;
                    if (maxThreads > 1) {
                        msg = String.format("[%s] %s", bm.getProjectId(), bm.getMessage());
                    } else {
                        msg = bm.getMessage();
                    }
                    log.accept(msg);
                } else {
                    prj.log.add(bm.getMessage());
                }
                break;
            }
            case Message.KEYBOARD_INPUT: {
                char keyStroke = ((StringMessage) entry).getMessage().charAt(0);
                switch (keyStroke) {
                    case KEY_PLUS:
                        linesPerProject = Math.min(10, linesPerProject + 1);
                        break;
                    case KEY_MINUS:
                        linesPerProject = Math.max(0, linesPerProject - 1);
                        break;
                    case KEY_CTRL_B:
                        noBuffering = !noBuffering;
                        if (noBuffering) {
                            applyNoBuffering();
                        } else {
                            clearDisplay();
                        }
                        break;
                    case KEY_CTRL_L:
                        clearDisplay();
                        break;
                    case KEY_CTRL_M:
                        displayDone = !displayDone;
                        displayDone();
                        break;
                }
                break;
            }
            case Message.TRANSFER_INITIATED:
            case Message.TRANSFER_STARTED:
            case Message.TRANSFER_PROGRESSED: {
                final TransferEvent te = (TransferEvent) entry;
                transfers
                        .computeIfAbsent(orEmpty(te.getProjectId()), p -> new LinkedHashMap<>())
                        .put(te.getResourceName(), te);
                break;
            }
            case Message.TRANSFER_CORRUPTED:
            case Message.TRANSFER_SUCCEEDED:
            case Message.TRANSFER_FAILED: {
                final TransferEvent te = (TransferEvent) entry;
                transfers
                        .computeIfAbsent(orEmpty(te.getProjectId()), p -> new LinkedHashMap<>())
                        .remove(te.getResourceName());
                break;
            }
            case Message.EXECUTION_FAILURE: {
                final ExecutionFailureEvent efe = (ExecutionFailureEvent) entry;
                failures.add(efe);
                break;
            }
            case Message.REQUEST_INPUT: {
                RequestInput ri = (RequestInput) entry;
                inputHandler.requestProjectInput(ri.getProjectId(), ri.getBytesToRead());
                break;
            }
            case Message.INPUT_DATA: {
                daemonDispatch.accept(entry);
                break;
            }
            default:
                throw new IllegalStateException("Unexpected message " + entry);
        }

        return true;
    }

    private String orEmpty(String s) {
        return s != null ? s : "";
    }

    private void applyNoBuffering() {
        projects.values().stream().flatMap(p -> p.log.stream()).forEach(log);
        projects.clear();
    }

    @Override
    public void describeTerminal() {
        StringBuilder sb = new StringBuilder();
        sb.append("Terminal: ").append(terminal != null ? terminal.getClass().getName() : null);
        if (terminal instanceof AbstractPosixTerminal) {
            sb.append(" with pty ")
                    .append(((AbstractPosixTerminal) terminal)
                            .getPty()
                            .getClass()
                            .getName());
        }
        this.accept(Message.log(sb.toString()));
    }

    @Override
    public int getTerminalWidth() {
        return terminal.getWidth();
    }

    public Terminal getTerminal() {
        return terminal;
    }

    private void clearDisplay() {
        if (!noBuffering && !dumb) {
            display.update(Collections.emptyList(), 0);
        }
    }

    private void displayDone() {
        if (displayDone) {
            try {
                log.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void close() throws Exception {
        closing = true;
        inputHandler.close();
        log.close();
        terminal.handle(Terminal.Signal.INT, previousIntHandler);
        terminal.close();
        if (exception != null) {
            throw exception;
        }
    }

    private void update() {
        if (noBuffering || dumb) {
            try {
                log.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return;
        }
        // no need to refresh the display at every single step
        final Size size = terminal.getSize();
        final int rows = size.getRows();
        final int cols = size.getColumns();
        display.resize(rows, size.getColumns());
        if (rows <= 0) {
            clearDisplay();
            return;
        }
        final List<AttributedString> lines = new ArrayList<>(rows);
        final int projectsCount = projects.size();

        int dispLines = rows;
        // status line
        dispLines--;
        // there's a bug which sometimes make the cursor goes one line below,
        // so keep one more line empty at the end
        dispLines--;

        addStatusLine(lines, dispLines, projectsCount);

        AttributedString globalFailure = formatFailures();
        if (globalFailure != null) {
            lines.add(globalFailure);
            dispLines--;
        }

        AttributedString globalTransfer = formatTransfers("");
        if (globalTransfer != null) {
            lines.add(globalTransfer);
            dispLines--;
        }

        if (projectsCount <= dispLines) {
            int remLogLines = dispLines - projectsCount;
            for (Project prj : projects.values()) {
                addProjectLine(lines, prj);
                // get the last lines of the project log, taking multi-line logs into account
                int nb = Math.min(remLogLines, linesPerProject);
                List<AttributedString> logs = lastN(prj.log, nb).stream()
                        .flatMap(s -> AttributedString.fromAnsi(s).columnSplitLength(Integer.MAX_VALUE).stream())
                        .map(s -> concat("   ", s))
                        .collect(lastN(nb));
                lines.addAll(logs);
                remLogLines -= logs.size();
            }
            while (remLogLines-- > 0 && lines.size() <= maxThreads + 1) {
                lines.add(AttributedString.EMPTY);
            }
        } else {
            int skipProjects = projectsCount - dispLines;
            for (Project prj : projects.values()) {
                if (skipProjects == 0) {
                    addProjectLine(lines, prj);
                } else {
                    skipProjects--;
                }
            }
        }
        List<AttributedString> trimmed =
                lines.stream().map(s -> s.columnSubSequence(0, cols)).collect(Collectors.toList());
        display.update(trimmed, -1);
    }

    private AttributedString formatFailures() {
        if (failures.isEmpty()) {
            return null;
        }
        AttributedStringBuilder asb = new AttributedStringBuilder();
        asb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).bold());
        if (failures.stream().anyMatch(ExecutionFailureEvent::isHalted)) {
            asb.append("ABORTING ");
        }
        asb.append("FAILURE: ");
        asb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.RED));
        if (failures.size() == 1) {
            ExecutionFailureEvent efe = failures.iterator().next();
            asb.append(efe.getProjectId());
            String exception = efe.getException();
            if (exception != null) {
                if (exception.startsWith("org.apache.maven.lifecycle.LifecycleExecutionException: ")) {
                    exception =
                            exception.substring("org.apache.maven.lifecycle.LifecycleExecutionException: ".length());
                }
                asb.append(": ").append(exception);
            }
        } else {
            asb.append(String.valueOf(failures.size())).append(" projects failed: ");
            asb.append(
                    failures.stream().map(ExecutionFailureEvent::getProjectId).collect(Collectors.joining(", ")));
        }
        AttributedString as = asb.toAttributedString();
        if (as.columnLength() >= getTerminalWidth() - 1) {
            asb = new AttributedStringBuilder();
            asb.append(as.columnSubSequence(0, getTerminalWidth() - 2));
            asb.style(AttributedStyle.DEFAULT);
            asb.append("…");
            as = asb.toAttributedString();
        }
        return as;
    }

    private AttributedString formatTransfers(String projectId) {
        Collection<TransferEvent> transfers =
                this.transfers.getOrDefault(projectId, Collections.emptyMap()).values();
        if (transfers.isEmpty()) {
            return null;
        }
        TransferEvent event = transfers.iterator().next();
        String action = event.getRequestType() == TransferEvent.PUT ? "Uploading" : "Downloading";
        if (transfers.size() == 1) {
            String direction = event.getRequestType() == TransferEvent.PUT ? "to" : "from";
            long cur = event.getTransferredBytes();
            long max = event.getContentLength();
            AttributedStringBuilder asb = new AttributedStringBuilder();
            asb.append(action);
            asb.append(' ');
            asb.style(AttributedStyle.BOLD);
            asb.append(pathToMaven(event.getResourceName()));
            asb.style(AttributedStyle.DEFAULT);
            asb.append(' ');
            asb.append(direction);
            asb.append(' ');
            asb.append(event.getRepositoryId());
            if (cur > 0 && cur < max) {
                asb.append(' ');
                asb.append(OsUtils.bytesToHumanReadable(cur));
                asb.append('/');
                asb.append(OsUtils.bytesToHumanReadable(max));
            }
            return asb.toAttributedString();
        } else {
            return new AttributedString(action + " " + transfers.size() + " files...");
        }
    }

    public static String pathToMaven(String location) {
        String[] p = location.split("/");
        if (p.length >= 4) {
            final String artifactId = p[p.length - 3];
            final String version = p[p.length - 2];
            final String fileName = p[p.length - 1];
            final int artifactIdVersionLength = artifactId.length() + 1 /* the dash */ + version.length();
            if (fileName.length() > artifactIdVersionLength) {
                /* We use fileName.length() > to avoid https://github.com/apache/maven-mvnd/issues/929
                 * In the code below, we assume that the fileName ends either with `.<extension>`
                 * or `-<classifier>.<extension>` */
                final int lastPeriodPos = fileName.lastIndexOf('.');
                if (lastPeriodPos >= 0) {
                    final String classifier;
                    final String type;
                    final StringBuilder sb = new StringBuilder();
                    if (fileName.charAt(artifactIdVersionLength) == '-') {
                        classifier = fileName.substring(artifactIdVersionLength + 1, lastPeriodPos);
                    } else {
                        classifier = null;
                    }
                    type = fileName.substring(lastPeriodPos + 1);
                    for (int j = 0; j < p.length - 3; j++) {
                        if (j > 0) {
                            sb.append('.');
                        }
                        sb.append(p[j]);
                    }
                    sb.append(':').append(artifactId).append(':').append(version);
                    if (!"jar".equals(type) || classifier != null) {
                        sb.append(':');
                        if (!"jar".equals(type)) {
                            sb.append(type);
                        }
                        if (classifier != null) {
                            sb.append(':').append(classifier);
                        }
                    }
                    return sb.toString();
                }
            }
        }
        return location;
    }

    private void addStatusLine(final List<AttributedString> lines, int dispLines, final int projectsCount) {
        if (name != null || buildStatus != null) {
            AttributedStringBuilder asb = new AttributedStringBuilder();
            if (name != null) {
                asb.append("Building ");
                asb.style(AttributedStyle.BOLD);
                asb.append(name);
                asb.style(AttributedStyle.DEFAULT);

                /* Daemon ID */
                asb.append("  daemon: ")
                        .style(AttributedStyle.BOLD)
                        .append(daemonId)
                        .style(AttributedStyle.DEFAULT);

                /* Threads */
                asb.append("  threads used/hidden/max: ")
                        .style(AttributedStyle.BOLD)
                        .append(String.format(
                                threadsFormat,
                                String.valueOf(projectsCount)
                                        + '/'
                                        + Math.max(0, projectsCount - dispLines)
                                        + '/'
                                        + maxThreads))
                        .style(AttributedStyle.DEFAULT);

                /* Progress */
                asb.append("  progress: ")
                        .style(AttributedStyle.BOLD)
                        .append(String.format(projectsDoneFomat, doneProjects))
                        .append('/')
                        .append(String.valueOf(totalProjects))
                        .append(' ')
                        .append(String.format("%3d", doneProjects * 100 / totalProjects))
                        .append('%')
                        .style(AttributedStyle.DEFAULT);

            } else {
                asb.style(AttributedStyle.BOLD).append(buildStatus).style(AttributedStyle.DEFAULT);
            }

            /* Time */
            long sec = (System.currentTimeMillis() - this.start) / 1000;
            asb.append("  time: ")
                    .style(AttributedStyle.BOLD)
                    .append(String.format("%02d:%02d", sec / 60, sec % 60))
                    .style(AttributedStyle.DEFAULT);

            lines.add(asb.toAttributedString());
        }
    }

    private void addProjectLine(final List<AttributedString> lines, Project prj) {
        final MojoStartedEvent execution = prj.runningExecution;
        final AttributedStringBuilder asb = new AttributedStringBuilder();
        AttributedString transfer = formatTransfers(prj.id);
        if (transfer != null) {
            asb.append(':')
                    .style(CYAN_FOREGROUND)
                    .append(String.format(artifactIdFormat, prj.id))
                    .style(AttributedStyle.DEFAULT)
                    .append(transfer);
        } else if (execution == null) {
            asb.append(':').style(CYAN_FOREGROUND).append(prj.id);
        } else {
            asb.append(':')
                    .style(CYAN_FOREGROUND)
                    .append(String.format(artifactIdFormat, prj.id))
                    .style(GREEN_FOREGROUND);
            if (execution.getPluginGoalPrefix().isEmpty()) {
                asb.append(execution.getPluginGroupId()).append(':').append(execution.getPluginArtifactId());
            } else {
                asb.append(execution.getPluginGoalPrefix());
            }
            asb.append(':')
                    .append(execution.getPluginVersion())
                    .append(':')
                    .append(execution.getMojo())
                    .append(' ')
                    .style(AttributedStyle.DEFAULT)
                    .append('(')
                    .append(execution.getExecutionId())
                    .append(')');
        }
        lines.add(asb.toAttributedString());
    }

    private static <T> List<T> lastN(List<T> list, int n) {
        return list.subList(Math.max(0, list.size() - n), list.size());
    }

    private static <T> Collector<T, ?, List<T>> lastN(int n) {
        return Collector.<T, Deque<T>, List<T>>of(
                ArrayDeque::new,
                (acc, t) -> {
                    if (n > 0) {
                        if (acc.size() == n) acc.pollFirst();
                        acc.add(t);
                    }
                },
                (acc1, acc2) -> {
                    while (acc2.size() < n && !acc1.isEmpty()) {
                        acc2.addFirst(acc1.pollLast());
                    }
                    return acc2;
                },
                ArrayList::new);
    }

    private static AttributedString concat(String s1, AttributedString s2) {
        AttributedStringBuilder asb = new AttributedStringBuilder();
        asb.append(s1);
        asb.append(s2);
        return asb.toAttributedString();
    }

    /**
     * A closeable string message consumer.
     */
    interface ClientLog extends Consumer<String> {

        void accept(String message);

        void flush() throws IOException;

        void close() throws IOException;
    }

    /**
     * A {@link ClientLog} backed by a file.
     */
    static class FileLog implements ClientLog {

        private final Writer out;
        private final Path logFile;

        public FileLog(Path logFile) throws IOException {
            super();
            this.out = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8);
            this.logFile = logFile;
        }

        @Override
        public void accept(String message) {
            try {
                out.write(message);
                out.write('\n');
            } catch (IOException e) {
                throw new RuntimeException("Could not write to " + logFile, e);
            }
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    /**
     * A {@link ClientLog} that first collects all incoming messages in a {@link List} and outputs them to a JLine
     * {@link Terminal} upon {@link #close()}.
     */
    class MessageCollector implements ClientLog {

        private final List<String> messages = new ArrayList<>();

        @Override
        public void accept(String message) {
            messages.add(message);
        }

        @Override
        public void flush() {
            clearDisplay();
            messages.forEach(System.out::println);
            messages.clear();
            terminal.flush();
        }

        @Override
        public void close() {
            flush();
        }
    }
}
