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
package org.mvndaemon.mvnd.common;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UTFDataFormatException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public abstract class Message {
    public static final int BUILD_REQUEST = 1;
    public static final int BUILD_STARTED = 2;
    public static final int BUILD_FINISHED = 3;
    /** A {@link StringMessage} bearing the {@code artifactId} of the project whose build just started */
    public static final int PROJECT_STARTED = 4;
    /** A {@link StringMessage} bearing the {@code artifactId} of the project whose build just finished */
    public static final int PROJECT_STOPPED = 5;

    public static final int MOJO_STARTED = 6;
    public static final int PROJECT_LOG_MESSAGE = 7;
    public static final int BUILD_LOG_MESSAGE = 8;
    public static final int BUILD_EXCEPTION = 9;
    public static final int KEEP_ALIVE = 10;
    public static final int STOP = 11;
    public static final int DISPLAY = 12;
    public static final int PROMPT = 13;
    public static final int PROMPT_RESPONSE = 14;
    public static final int BUILD_STATUS = 15;
    public static final int KEYBOARD_INPUT = 16;
    public static final int CANCEL_BUILD = 17;
    public static final int TRANSFER_INITIATED = 18;
    public static final int TRANSFER_STARTED = 19;
    public static final int TRANSFER_PROGRESSED = 20;
    public static final int TRANSFER_CORRUPTED = 21;
    public static final int TRANSFER_SUCCEEDED = 22;
    public static final int TRANSFER_FAILED = 23;
    public static final int EXECUTION_FAILURE = 24;
    public static final int PRINT_OUT = 25;
    public static final int PRINT_ERR = 26;
    public static final int REQUEST_INPUT = 27;
    public static final int INPUT_DATA = 28;

    final int type;

    Message(int type) {
        this.type = type;
    }

    public static Message read(DataInputStream input) throws IOException {
        int type = input.read();
        if (type < 0) {
            return null;
        }
        switch (type) {
            case BUILD_REQUEST:
                return BuildRequest.read(input);
            case BUILD_STARTED:
                return BuildStarted.read(input);
            case BUILD_FINISHED:
                return BuildFinished.read(input);
            case MOJO_STARTED:
                return MojoStartedEvent.read(input);
            case PROJECT_LOG_MESSAGE:
            case DISPLAY:
                return ProjectEvent.read(type, input);
            case BUILD_EXCEPTION:
                return BuildException.read(input);
            case KEEP_ALIVE:
                return BareMessage.KEEP_ALIVE_SINGLETON;
            case STOP:
                return BareMessage.STOP_SINGLETON;
            case PROMPT:
                return Prompt.read(input);
            case PROMPT_RESPONSE:
                return PromptResponse.read(input);
            case PROJECT_STARTED:
            case PROJECT_STOPPED:
            case BUILD_STATUS:
            case BUILD_LOG_MESSAGE:
                return StringMessage.read(type, input);
            case CANCEL_BUILD:
                return BareMessage.CANCEL_BUILD_SINGLETON;
            case TRANSFER_INITIATED:
            case TRANSFER_STARTED:
            case TRANSFER_PROGRESSED:
            case TRANSFER_CORRUPTED:
            case TRANSFER_SUCCEEDED:
            case TRANSFER_FAILED:
                return TransferEvent.read(type, input);
            case EXECUTION_FAILURE:
                return ExecutionFailureEvent.read(input);
            case PRINT_OUT:
            case PRINT_ERR:
                return StringMessage.read(type, input);
            case REQUEST_INPUT:
                return RequestInput.read(input);
            case INPUT_DATA:
                return InputData.read(input);
        }
        throw new IllegalStateException("Unexpected message type: " + type);
    }

    private static final AtomicLong sequence = new AtomicLong();

    final long seq = sequence.incrementAndGet();
    final long timestamp = System.nanoTime();

    public static Comparator<Message> getMessageComparator() {
        return Comparator.comparingInt(Message::getClassOrder).thenComparingLong(Message::seq);
    }

    public static int getClassOrder(Message m) {
        switch (m.getType()) {
            case KEEP_ALIVE:
            case BUILD_REQUEST:
                return 0;
            case BUILD_STARTED:
                return 1;
            case PROMPT:
            case PROMPT_RESPONSE:
            case DISPLAY:
            case PRINT_OUT:
            case PRINT_ERR:
            case REQUEST_INPUT:
            case INPUT_DATA:
                return 2;
            case PROJECT_STARTED:
                return 3;
            case MOJO_STARTED:
                return 4;
            case EXECUTION_FAILURE:
                return 10;
            case TRANSFER_INITIATED:
            case TRANSFER_STARTED:
                return 40;
            case TRANSFER_PROGRESSED:
                return 41;
            case TRANSFER_CORRUPTED:
            case TRANSFER_SUCCEEDED:
            case TRANSFER_FAILED:
                return 42;
            case PROJECT_LOG_MESSAGE:
                return 50;
            case BUILD_LOG_MESSAGE:
                return 51;
            case PROJECT_STOPPED:
                return 95;
            case BUILD_FINISHED:
                return 96;
            case BUILD_EXCEPTION:
                return 97;
            case STOP:
                return 99;
            default:
                throw new IllegalStateException("Unexpected message type " + m.getType() + ": " + m);
        }
    }

    public long seq() {
        return seq;
    }

    public long timestamp() {
        return timestamp;
    }

    public void write(DataOutputStream output) throws IOException {
        output.write(type);
    }

    static void writeStringList(DataOutputStream output, List<String> value) throws IOException {
        output.writeInt(value.size());
        for (String v : value) {
            writeUTF(output, v);
        }
    }

    static void writeStringMap(DataOutputStream output, Map<String, String> value) throws IOException {
        output.writeInt(value.size());
        for (Map.Entry<String, String> e : value.entrySet()) {
            writeUTF(output, e.getKey());
            writeUTF(output, e.getValue());
        }
    }

    static List<String> readStringList(DataInputStream input) throws IOException {
        ArrayList<String> l = new ArrayList<>();
        int nb = input.readInt();
        for (int i = 0; i < nb; i++) {
            l.add(readUTF(input));
        }
        return l;
    }

    static Map<String, String> readStringMap(DataInputStream input) throws IOException {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        int nb = input.readInt();
        for (int i = 0; i < nb; i++) {
            String k = readUTF(input);
            String v = readUTF(input);
            m.put(k, v);
        }
        return m;
    }

    private static final String INVALID_BYTE = "Invalid byte";
    private static final int UTF_BUFS_CHAR_CNT = 256;
    private static final int UTF_BUFS_BYTE_CNT = UTF_BUFS_CHAR_CNT * 3;
    private static final ThreadLocal<byte[]> BUF_TLS = ThreadLocal.withInitial(() -> new byte[UTF_BUFS_BYTE_CNT]);

    static String readUTF(DataInputStream input) throws IOException {
        byte[] byteBuf = BUF_TLS.get();
        int len = input.readInt();
        if (len == -1) {
            return null;
        }
        final char[] chars = new char[len];
        int i = 0, cnt = 0, charIdx = 0;
        while (charIdx < len) {
            if (i == cnt) {
                cnt = input.read(byteBuf, 0, Math.min(UTF_BUFS_BYTE_CNT, len - charIdx));
                if (cnt < 0) {
                    throw new EOFException();
                }
                i = 0;
            }
            final int a = byteBuf[i++] & 0xff;
            if (a < 0x80) {
                // low bit clear
                chars[charIdx++] = (char) a;
            } else if (a < 0xc0) {
                throw new UTFDataFormatException(INVALID_BYTE);
            } else if (a < 0xe0) {
                if (i == cnt) {
                    cnt = input.read(byteBuf, 0, Math.min(UTF_BUFS_BYTE_CNT, len - charIdx));
                    if (cnt < 0) {
                        throw new EOFException();
                    }
                    i = 0;
                }
                final int b = byteBuf[i++] & 0xff;
                if ((b & 0xc0) != 0x80) {
                    throw new UTFDataFormatException(INVALID_BYTE);
                }
                chars[charIdx++] = (char) ((a & 0x1f) << 6 | b & 0x3f);
            } else if (a < 0xf0) {
                if (i == cnt) {
                    cnt = input.read(byteBuf, 0, Math.min(UTF_BUFS_BYTE_CNT, len - charIdx));
                    if (cnt < 0) {
                        throw new EOFException();
                    }
                    i = 0;
                }
                final int b = byteBuf[i++] & 0xff;
                if ((b & 0xc0) != 0x80) {
                    throw new UTFDataFormatException(INVALID_BYTE);
                }
                if (i == cnt) {
                    cnt = input.read(byteBuf, 0, Math.min(UTF_BUFS_BYTE_CNT, len - charIdx));
                    if (cnt < 0) {
                        throw new EOFException();
                    }
                    i = 0;
                }
                final int c = byteBuf[i++] & 0xff;
                if ((c & 0xc0) != 0x80) {
                    throw new UTFDataFormatException(INVALID_BYTE);
                }
                chars[charIdx++] = (char) ((a & 0x0f) << 12 | (b & 0x3f) << 6 | c & 0x3f);
            } else {
                throw new UTFDataFormatException(INVALID_BYTE);
            }
        }
        return String.valueOf(chars);
    }

    static void writeUTF(DataOutputStream output, String s) throws IOException {
        byte[] byteBuf = BUF_TLS.get();
        if (s == null) {
            output.writeInt(-1);
            return;
        }
        final int length = s.length();
        output.writeInt(length);
        int strIdx = 0;
        int byteIdx = 0;
        while (strIdx < length) {
            final char c = s.charAt(strIdx++);
            if (c > 0 && c <= 0x7f) {
                byteBuf[byteIdx++] = (byte) c;
            } else if (c <= 0x07ff) {
                byteBuf[byteIdx++] = (byte) (0xc0 | 0x1f & c >> 6);
                byteBuf[byteIdx++] = (byte) (0x80 | 0x3f & c);
            } else {
                byteBuf[byteIdx++] = (byte) (0xe0 | 0x0f & c >> 12);
                byteBuf[byteIdx++] = (byte) (0x80 | 0x3f & c >> 6);
                byteBuf[byteIdx++] = (byte) (0x80 | 0x3f & c);
            }
            if (byteIdx > UTF_BUFS_BYTE_CNT - 4) {
                output.write(byteBuf, 0, byteIdx);
                byteIdx = 0;
            }
        }
        if (byteIdx > 0) {
            output.write(byteBuf, 0, byteIdx);
        }
    }

    public static class BuildRequest extends Message {
        final List<String> args;
        final String workingDir;
        final String projectDir;
        final Map<String, String> env;

        public static Message read(DataInputStream input) throws IOException {
            List<String> args = readStringList(input);
            String workingDir = readUTF(input);
            String projectDir = readUTF(input);
            Map<String, String> env = readStringMap(input);
            return new BuildRequest(args, workingDir, projectDir, env);
        }

        public BuildRequest(List<String> args, String workingDir, String projectDir, Map<String, String> env) {
            super(BUILD_REQUEST);
            this.args = args;
            this.workingDir = workingDir;
            this.projectDir = projectDir;
            this.env = env;
        }

        public List<String> getArgs() {
            return args;
        }

        public String getWorkingDir() {
            return workingDir;
        }

        public String getProjectDir() {
            return projectDir;
        }

        public Map<String, String> getEnv() {
            return env;
        }

        @Override
        public String toString() {
            return "BuildRequest{" + "args="
                    + args + ", workingDir='"
                    + workingDir + '\'' + ", projectDir='"
                    + projectDir + '\'' + '}';
        }

        @Override
        public void write(DataOutputStream output) throws IOException {
            super.write(output);
            writeStringList(output, args);
            writeUTF(output, workingDir);
            writeUTF(output, projectDir);
            writeStringMap(output, env);
        }
    }

    public static class BuildFinished extends Message {
        final int exitCode;

        public static Message read(DataInputStream input) throws IOException {
            int exitCode = input.readInt();
            return new BuildFinished(exitCode);
        }

        public BuildFinished(int exitCode) {
            super(BUILD_FINISHED);
            this.exitCode = exitCode;
        }

        @Override
        public String toString() {
            return "BuildFinished{exitCode=" + exitCode + '}';
        }

        @Override
        public void write(DataOutputStream output) throws IOException {
            super.write(output);
            output.writeInt(exitCode);
        }

        public int getExitCode() {
            return exitCode;
        }
    }

    public static class BuildException extends Message {
        final String message;
        final String className;
        final String stackTrace;

        public static Message read(DataInputStream input) throws IOException {
            String message = readUTF(input);
            String className = readUTF(input);
            String stackTrace = readUTF(input);
            return new BuildException(message, className, stackTrace);
        }

        public BuildException(Throwable t) {
            this(t.getMessage(), t.getClass().getName(), getStackTrace(t));
        }

        public static String getStackTrace(Throwable t) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw, true));
            return sw.toString();
        }

        public BuildException(String message, String className, String stackTrace) {
            super(BUILD_EXCEPTION);
            this.message = message;
            this.className = className;
            this.stackTrace = stackTrace;
        }

        public String getMessage() {
            return message;
        }

        public String getClassName() {
            return className;
        }

        public String getStackTrace() {
            return stackTrace;
        }

        @Override
        public String toString() {
            return "BuildException{" + "message='"
                    + message + '\'' + ", className='"
                    + className + '\'' + ", stackTrace='"
                    + stackTrace + '\'' + '}';
        }

        @Override
        public void write(DataOutputStream output) throws IOException {
            super.write(output);
            writeUTF(output, message);
            writeUTF(output, className);
            writeUTF(output, stackTrace);
        }
    }

    public static class ProjectEvent extends Message {
        final String projectId;
        final String message;

        public static Message read(int type, DataInputStream input) throws IOException {
            String projectId = readUTF(input);
            String message = readUTF(input);
            return new ProjectEvent(type, projectId, message);
        }

        private ProjectEvent(int type, String projectId, String message) {
            super(type);
            this.projectId = Objects.requireNonNull(projectId, "projectId cannot be null");
            this.message = Objects.requireNonNull(message, "message cannot be null");
        }

        public String getProjectId() {
            return projectId;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return mnemonic() + "{" + "projectId='" + projectId + '\'' + ", message='" + message + '\'' + '}';
        }

        private String mnemonic() {
            switch (type) {
                case DISPLAY:
                    return "Display";
                case PROJECT_LOG_MESSAGE:
                    return "ProjectLogMessage";
                default:
                    throw new IllegalStateException("Unexpected type " + type);
            }
        }

        @Override
        public void write(DataOutputStream output) throws IOException {
            super.write(output);
            writeUTF(output, projectId);
            writeUTF(output, message);
        }
    }

    public static class MojoStartedEvent extends Message {
        final String artifactId;
        final String pluginGroupId;
        final String pluginArtifactId;
        final String pluginGoalPrefix;
        final String pluginVersion;
        final String mojo;
        final String executionId;

        public static MojoStartedEvent read(DataInputStream input) throws IOException {
            final String artifactId = readUTF(input);
            final String pluginGroupId = readUTF(input);
            final String pluginArtifactId = readUTF(input);
            final String pluginGoalPrefix = readUTF(input);
            final String pluginVersion = readUTF(input);
            final String mojo = readUTF(input);
            final String executionId = readUTF(input);
            return new MojoStartedEvent(
                    artifactId, pluginGroupId, pluginArtifactId, pluginGoalPrefix, pluginVersion, mojo, executionId);
        }

        public MojoStartedEvent(
                String artifactId,
                String pluginGroupId,
                String pluginArtifactId,
                String pluginGoalPrefix,
                String pluginVersion,
                String mojo,
                String executionId) {
            super(Message.MOJO_STARTED);
            this.artifactId = Objects.requireNonNull(artifactId, "artifactId cannot be null");
            this.pluginGroupId = Objects.requireNonNull(pluginGroupId, "pluginGroupId cannot be null");
            this.pluginArtifactId = Objects.requireNonNull(pluginArtifactId, "pluginArtifactId cannot be null");
            this.pluginGoalPrefix = Objects.requireNonNull(pluginGoalPrefix, "pluginGoalPrefix cannot be null");
            this.pluginVersion = Objects.requireNonNull(pluginVersion, "pluginVersion cannot be null");
            this.mojo = Objects.requireNonNull(mojo, "mojo cannot be null");
            this.executionId = Objects.requireNonNull(executionId, "executionId cannot be null");
        }

        public String getArtifactId() {
            return artifactId;
        }

        public String getPluginGroupId() {
            return pluginGroupId;
        }

        public String getPluginArtifactId() {
            return pluginArtifactId;
        }

        public String getPluginGoalPrefix() {
            return pluginGoalPrefix;
        }

        public String getPluginVersion() {
            return pluginVersion;
        }

        public String getExecutionId() {
            return executionId;
        }

        public String getMojo() {
            return mojo;
        }

        @Override
        public String toString() {
            return "MojoStarted{"
                    + "artifactId='" + artifactId + '\'' + ", "
                    + "pluginGroupId='" + pluginGroupId + '\'' + ", "
                    + "pluginArtifactId='" + pluginArtifactId + '\'' + ", "
                    + "pluginGoalPrefix='" + pluginGoalPrefix + '\'' + ", "
                    + "pluginVersion='" + pluginVersion + '\'' + ", "
                    + "mojo='" + mojo + '\'' + ", "
                    + "executionId='" + executionId + '\'' + '}';
        }

        @Override
        public void write(DataOutputStream output) throws IOException {
            super.write(output);
            writeUTF(output, artifactId);
            writeUTF(output, pluginGroupId);
            writeUTF(output, pluginArtifactId);
            writeUTF(output, pluginGoalPrefix);
            writeUTF(output, pluginVersion);
            writeUTF(output, mojo);
            writeUTF(output, executionId);
        }
    }

    public static class BuildStarted extends Message {

        final String projectId;
        final int projectCount;
        final int maxThreads;
        final int artifactIdDisplayLength;

        public static BuildStarted read(DataInputStream input) throws IOException {
            final String projectId = readUTF(input);
            final int projectCount = input.readInt();
            final int maxThreads = input.readInt();
            final int artifactIdDisplayLength = input.readInt();
            return new BuildStarted(projectId, projectCount, maxThreads, artifactIdDisplayLength);
        }

        public BuildStarted(String projectId, int projectCount, int maxThreads, int artifactIdDisplayLength) {
            super(BUILD_STARTED);
            this.projectId = projectId;
            this.projectCount = projectCount;
            this.maxThreads = maxThreads;
            this.artifactIdDisplayLength = artifactIdDisplayLength;
        }

        public String getProjectId() {
            return projectId;
        }

        public int getProjectCount() {
            return projectCount;
        }

        public int getMaxThreads() {
            return maxThreads;
        }

        public int getArtifactIdDisplayLength() {
            return artifactIdDisplayLength;
        }

        @Override
        public String toString() {
            return "BuildStarted{" + "projectId='"
                    + projectId + "', projectCount=" + projectCount + ", maxThreads="
                    + maxThreads + ", artifactIdDisplayLength=" + artifactIdDisplayLength + "}";
        }

        @Override
        public void write(DataOutputStream output) throws IOException {
            super.write(output);
            writeUTF(output, projectId);
            output.writeInt(projectCount);
            output.writeInt(maxThreads);
            output.writeInt(artifactIdDisplayLength);
        }
    }

    public static class BareMessage extends Message {

        public static final BareMessage KEEP_ALIVE_SINGLETON = new BareMessage(KEEP_ALIVE);
        public static final BareMessage STOP_SINGLETON = new BareMessage(STOP);
        public static final BareMessage CANCEL_BUILD_SINGLETON = new BareMessage(CANCEL_BUILD);

        private BareMessage(int type) {
            super(type);
        }

        @Override
        public String toString() {
            switch (type) {
                case KEEP_ALIVE:
                    return "KeepAlive";
                case BUILD_FINISHED:
                    return "BuildStopped";
                case STOP:
                    return "Stop";
                case CANCEL_BUILD:
                    return "BuildCanceled";
                default:
                    throw new IllegalStateException("Unexpected type " + type);
            }
        }
    }

    public static class StringMessage extends Message {

        final String message;

        public static StringMessage read(int type, DataInputStream input) throws IOException {
            String message = readUTF(input);
            return new StringMessage(type, message);
        }

        private StringMessage(int type, String message) {
            super(type);
            this.message = message;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public void write(DataOutputStream output) throws IOException {
            super.write(output);
            writeUTF(output, message);
        }

        @Override
        public String toString() {
            return mnemonic() + "{payload='" + message + "'}";
        }

        private String mnemonic() {
            switch (type) {
                case PROJECT_STARTED:
                    return "ProjectStarted";
                case PROJECT_STOPPED:
                    return "ProjectStopped";
                case BUILD_STATUS:
                    return "BuildStatus";
                case KEYBOARD_INPUT:
                    return "KeyboardInput";
                case BUILD_LOG_MESSAGE:
                    return "BuildLogMessage";
                case PRINT_OUT:
                    return "PrintOut";
                case PRINT_ERR:
                    return "PrintErr";
                default:
                    throw new IllegalStateException("Unexpected type " + type);
            }
        }
    }

    public static class Prompt extends Message {

        final String projectId;
        final String uid;
        final String message;
        final boolean password;

        public static Prompt read(DataInputStream input) throws IOException {
            String projectId = Message.readUTF(input);
            String uid = Message.readUTF(input);
            String message = Message.readUTF(input);
            boolean password = input.readBoolean();
            return new Prompt(projectId, uid, message, password);
        }

        public Prompt(String projectId, String uid, String message, boolean password) {
            super(PROMPT);
            this.projectId = projectId;
            this.uid = uid;
            this.message = message;
            this.password = password;
        }

        public String getProjectId() {
            return projectId;
        }

        public String getUid() {
            return uid;
        }

        public String getMessage() {
            return message;
        }

        public boolean isPassword() {
            return password;
        }

        @Override
        public String toString() {
            return "Prompt{" + "projectId='"
                    + projectId + '\'' + ", uid='"
                    + uid + '\'' + ", message='"
                    + message + '\'' + ", password="
                    + password + '}';
        }

        @Override
        public void write(DataOutputStream output) throws IOException {
            super.write(output);
            writeUTF(output, projectId);
            writeUTF(output, uid);
            writeUTF(output, message);
            output.writeBoolean(password);
        }

        public PromptResponse response(String message) {
            return new PromptResponse(projectId, uid, message);
        }
    }

    public static class PromptResponse extends Message {

        final String projectId;
        final String uid;
        final String message;

        public static Message read(DataInputStream input) throws IOException {
            String projectId = Message.readUTF(input);
            String uid = Message.readUTF(input);
            String message = Message.readUTF(input);
            return new PromptResponse(projectId, uid, message);
        }

        private PromptResponse(String projectId, String uid, String message) {
            super(PROMPT_RESPONSE);
            this.projectId = projectId;
            this.uid = uid;
            this.message = message;
        }

        public String getProjectId() {
            return projectId;
        }

        public String getUid() {
            return uid;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return "PromptResponse{" + "projectId='"
                    + projectId + '\'' + ", uid='"
                    + uid + '\'' + ", message='"
                    + message + '\'' + '}';
        }

        @Override
        public void write(DataOutputStream output) throws IOException {
            super.write(output);
            writeUTF(output, projectId);
            writeUTF(output, uid);
            writeUTF(output, message);
        }
    }

    public static class ExecutionFailureEvent extends Message {

        final String projectId;
        final boolean halted;
        final String exception;

        private ExecutionFailureEvent(String projectId, boolean halted, String exception) {
            super(EXECUTION_FAILURE);
            this.projectId = projectId;
            this.halted = halted;
            this.exception = exception;
        }

        public String getProjectId() {
            return projectId;
        }

        public boolean isHalted() {
            return halted;
        }

        public String getException() {
            return exception;
        }

        @Override
        public String toString() {
            return "ExecutionFailure{" + "projectId='"
                    + projectId + '\'' + ", halted="
                    + halted + ", exception='"
                    + exception + '\'' + '}';
        }

        @Override
        public void write(DataOutputStream output) throws IOException {
            super.write(output);
            writeUTF(output, projectId);
            output.writeBoolean(halted);
            writeUTF(output, exception);
        }

        public static ExecutionFailureEvent read(DataInputStream input) throws IOException {
            String projectId = readUTF(input);
            boolean halted = input.readBoolean();
            String exception = readUTF(input);
            return new ExecutionFailureEvent(projectId, halted, exception);
        }
    }

    public static class TransferEvent extends Message {

        public static final int INITIATED = 0;
        public static final int STARTED = 1;
        public static final int PROGRESSED = 2;
        public static final int CORRUPTED = 3;
        public static final int SUCCEEDED = 4;
        public static final int FAILED = 5;

        public static final int GET = 0;
        public static final int GET_EXISTENCE = 1;
        public static final int PUT = 2;

        final String projectId;
        final int requestType;
        final String repositoryId;
        final String repositoryUrl;
        final String resourceName;
        final long contentLength;
        final long transferredBytes;
        final String exception;

        private TransferEvent(
                int type,
                String projectId,
                int requestType,
                String repositoryId,
                String repositoryUrl,
                String resourceName,
                long contentLength,
                long transferredBytes,
                String exception) {
            super(type);
            this.projectId = projectId;
            this.requestType = requestType;
            this.repositoryId = repositoryId;
            this.repositoryUrl = repositoryUrl;
            this.resourceName = resourceName;
            this.contentLength = contentLength;
            this.transferredBytes = transferredBytes;
            this.exception = exception;
        }

        public String getProjectId() {
            return projectId;
        }

        public int getRequestType() {
            return requestType;
        }

        public String getRepositoryId() {
            return repositoryId;
        }

        public String getRepositoryUrl() {
            return repositoryUrl;
        }

        public String getResourceName() {
            return resourceName;
        }

        public long getContentLength() {
            return contentLength;
        }

        public long getTransferredBytes() {
            return transferredBytes;
        }

        public String getException() {
            return exception;
        }

        @Override
        public String toString() {
            return mnemonic() + "{" + "projectId="
                    + projectId + ", requestType="
                    + requestType + ", repositoryId='"
                    + repositoryId + '\'' + ", repositoryUrl='"
                    + repositoryUrl + '\'' + ", resourceName='"
                    + resourceName + '\'' + ", contentLength="
                    + contentLength + ", transferredBytes="
                    + transferredBytes
                    + (exception != null ? ", exception='" + exception + '\'' : "") + '}';
        }

        private String mnemonic() {
            switch (type) {
                case TRANSFER_INITIATED:
                    return "TransferInitiated";
                case TRANSFER_STARTED:
                    return "TransferStarted";
                case TRANSFER_PROGRESSED:
                    return "TransferProgressed";
                case TRANSFER_CORRUPTED:
                    return "TransferCorrupted";
                case TRANSFER_SUCCEEDED:
                    return "TransferSucceeded";
                case TRANSFER_FAILED:
                    return "TransferFailed";
                default:
                    throw new IllegalStateException("Unexpected type " + type);
            }
        }

        @Override
        public void write(DataOutputStream output) throws IOException {
            super.write(output);
            writeUTF(output, projectId);
            output.writeByte(requestType);
            writeUTF(output, repositoryId);
            writeUTF(output, repositoryUrl);
            writeUTF(output, resourceName);
            output.writeLong(contentLength);
            output.writeLong(transferredBytes);
            writeUTF(output, exception);
        }

        public static TransferEvent read(int type, DataInputStream input) throws IOException {
            String projectId = readUTF(input);
            int request = input.readByte();
            String repositoryId = readUTF(input);
            String repositoryUrl = readUTF(input);
            String resourceName = readUTF(input);
            long contentLength = input.readLong();
            long transferredBytes = input.readLong();
            String exception = readUTF(input);
            return new TransferEvent(
                    type,
                    projectId,
                    request,
                    repositoryId,
                    repositoryUrl,
                    resourceName,
                    contentLength,
                    transferredBytes,
                    exception);
        }
    }

    public static class RequestInput extends Message {

        private String projectId;
        private int bytesToRead;

        public static RequestInput read(DataInputStream input) throws IOException {
            String projectId = readUTF(input);
            int bytesToRead = input.readInt();
            return new RequestInput(projectId, bytesToRead);
        }

        public RequestInput(String projectId, int bytesToRead) {
            super(REQUEST_INPUT);
            this.projectId = projectId;
            this.bytesToRead = bytesToRead;
        }

        public String getProjectId() {
            return projectId;
        }

        public int getBytesToRead() {
            return bytesToRead;
        }

        @Override
        public String toString() {
            return "RequestInput{" + "projectId='" + projectId + '\'' + ", bytesToRead=" + bytesToRead + '}';
        }

        @Override
        public void write(DataOutputStream output) throws IOException {
            super.write(output);
            writeUTF(output, projectId);
            output.writeInt(bytesToRead);
        }
    }

    public static class InputData extends Message {

        final String data;

        public static Message read(DataInputStream input) throws IOException {
            String data = readUTF(input);
            return new InputData(data);
        }

        private InputData(String data) {
            super(INPUT_DATA);
            this.data = data;
        }

        public String getData() {
            return data;
        }

        public boolean isEof() {
            return data == null;
        }

        @Override
        public String toString() {
            return "InputResponse{" + (data == null ? "eof" : "data='" + data + "'") + '}';
        }

        @Override
        public void write(DataOutputStream output) throws IOException {
            super.write(output);
            writeUTF(output, data);
        }
    }

    public int getType() {
        return type;
    }

    public static StringMessage buildStatus(String payload) {
        return new StringMessage(BUILD_STATUS, payload);
    }

    public static RequestInput requestInput(String projectId, int bytesToRead) {
        return new RequestInput(projectId, bytesToRead);
    }

    public static InputData inputResponse(String data) {
        return new InputData(data);
    }

    public static InputData inputEof() {
        return new InputData(null);
    }

    public static StringMessage out(String message) {
        return new StringMessage(PRINT_OUT, message);
    }

    public static StringMessage err(String message) {
        return new StringMessage(PRINT_ERR, message);
    }

    public static StringMessage log(String message) {
        return new StringMessage(BUILD_LOG_MESSAGE, message);
    }

    public static ProjectEvent log(String projectId, String message) {
        return new ProjectEvent(PROJECT_LOG_MESSAGE, projectId, message);
    }

    public static StringMessage keyboardInput(char keyStroke) {
        return new StringMessage(KEYBOARD_INPUT, String.valueOf(keyStroke));
    }

    public static StringMessage projectStarted(String projectId) {
        return new StringMessage(PROJECT_STARTED, projectId);
    }

    public static StringMessage projectStopped(String projectId) {
        return new StringMessage(PROJECT_STOPPED, projectId);
    }

    public static ExecutionFailureEvent executionFailure(String projectId, boolean halted, String exception) {
        return new ExecutionFailureEvent(projectId, halted, exception);
    }

    public static Message mojoStarted(
            String artifactId,
            String pluginGroupId,
            String pluginArtifactId,
            String pluginGoalPrefix,
            String pluginVersion,
            String mojo,
            String executionId) {
        return new MojoStartedEvent(
                artifactId, pluginGroupId, pluginArtifactId, pluginGoalPrefix, pluginVersion, mojo, executionId);
    }

    public static ProjectEvent display(String projectId, String message) {
        return new ProjectEvent(Message.DISPLAY, projectId, message);
    }

    public static TransferEvent transfer(
            String projectId,
            int transferEventType,
            int requestType,
            String repositoryId,
            String repositoryUrl,
            String resourceName,
            long contentLength,
            long transferredBytes,
            String exception) {
        return new TransferEvent(
                transferEventType,
                projectId,
                requestType,
                repositoryId,
                repositoryUrl,
                resourceName,
                contentLength,
                transferredBytes,
                exception);
    }
}
