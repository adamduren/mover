package com.alto.mover;

import android.util.Log;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.UUID;
import java.util.Vector;


public class BaseMover {
    private final HashMap<String, ChannelSftp> mSftpChannels = new HashMap<String, ChannelSftp>();
    private final HashMap<String, FTPClient> mFtpChannels = new HashMap<String, FTPClient>();

    String mTestFilename = "_altoTest";

    private interface SftpOp {
        void run(ChannelSftp channel, FTPClient client, JSONObject args) throws Exception;
    }

    public interface IMoverInterface {
        void success(String message);
        void success(ArrayList<HashMap<String, String>> messages);
        void success();
        void error(String message);
    }

    private void _testConnectionFtp(String user, String password, String host, int port, IMoverInterface callbackContext) {
        FTPClient ftpClient = new FTPClient();
        try {
            ftpClient.connect(host, port);

            int replyCode = ftpClient.getReplyCode();
            if (!FTPReply.isPositiveCompletion(replyCode)) {
                callbackContext.error("Operation failed. Server reply code: \" + replyCode");
                return;
            }

            boolean success = ftpClient.login(user, password);

            if (!success) {
                callbackContext.error("Bad username or password");
                return;
            }

            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            ftpClient.enterLocalPassiveMode();

            OutputStream output = ftpClient.storeFileStream(mTestFilename);
            output.write("Hello Alto".getBytes());
            output.close();

            if (!ftpClient.completePendingCommand()) {
                callbackContext.error("Could not write file");
            }

            ftpClient.deleteFile(mTestFilename);
            ftpClient.logout();
            ftpClient.disconnect();

            callbackContext.success();
        } catch (IOException e) {
            callbackContext.error(e.getMessage());
        }
    }

    private void _testConnectionSftp(String user, String password, String host, int port, IMoverInterface callbackContext) {
        try {
            JSch jsch = new JSch();

            Session session = jsch.getSession(user, host, port);
            session.setPassword(password);
            session.setConfig("StrictHostKeyChecking", "no");
            session.connect();

            ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect();

            OutputStream output = channel.put(mTestFilename);
            output.write("Hello Alto".getBytes());
            output.close();

            channel.rm(mTestFilename);
            channel.disconnect();

            session.disconnect();

            callbackContext.success();
        } catch (Exception e) {
            callbackContext.error(e.getMessage());
        }
    }

    public void testConnection(final JSONObject args, final IMoverInterface callbackContext) {
        final String user;
        final String password;
        final String host;
        final String protocol;
        final int port;
        try {
            user = args.getString("user");
            password = args.getString("password");
            host = args.getString("host");
            protocol = args.getString("protocol");
        } catch (JSONException e) {
            callbackContext.error(e.getMessage());
            return;
        }

        if (protocol.equals("SFTP")) {
            port = args.optInt("port", 22);
            _testConnectionSftp(user, password, host, port, callbackContext);
        } else {
            port = args.optInt("port", 21);
            _testConnectionFtp(user, password, host, port, callbackContext);
        }
    }

    private void _connectSftp(String user, String password, String host, int port, IMoverInterface callbackContext) throws JSchException {
        JSch jsch = new JSch();

        Session session = jsch.getSession(user, host, port);
        session.setPassword(password);
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect();

        ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
        channel.connect();

        String key = UUID.randomUUID().toString();
        mSftpChannels.put(key, channel);

        callbackContext.success(key);
    }

    private void _connectFtp(String user, String password, String host, int port, IMoverInterface callbackContext) throws IOException {
        FTPClient ftpClient = new FTPClient();
        ftpClient.connect(host, port);

        int replyCode = ftpClient.getReplyCode();
        if (!FTPReply.isPositiveCompletion(replyCode)) {
            callbackContext.error("Operation failed. Server reply code: \" + replyCode");
            return;
        }

        boolean success = ftpClient.login(user, password);

        if (!success) {
            callbackContext.error("Bad username or password");
            return;
        }

        ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
        ftpClient.enterLocalPassiveMode();

        String key = UUID.randomUUID().toString();
        mFtpChannels.put(key, ftpClient);

        callbackContext.success(key);
    }

    public void connect(final JSONObject args, final IMoverInterface callbackContext) {
        try {
            final String user;
            final String password;
            final String host;
            final int port;
            final String protocol;

            try {
                user = args.getString("user");
                password = args.getString("password");
                host = args.getString("host");
                protocol = args.getString("protocol");
            } catch (JSONException e) {
                callbackContext.error(e.getMessage());
                return;
            }

            if (protocol.equals("SFTP")) {
                port = args.optInt("port", 22);
                _connectSftp(user, password, host, port, callbackContext);
            } else {
                port = args.optInt("port", 21);
                _connectFtp(user, password, host, port, callbackContext);
            }
        } catch (Exception e) {
            callbackContext.error(e.getMessage());
        }
    }

    public void disconnect(JSONObject args, IMoverInterface callbackContext) {
        String channelId;
        String protocol;

        try {
            channelId = args.getString("key");
            protocol = args.getString("protocol");
        } catch (JSONException e) {
            callbackContext.error(e.getMessage());
            return;
        }

        if (protocol.equals("SFTP")) {
            ChannelSftp channel = mSftpChannels.get(channelId);
            Session session = null;

            if (channel == null) {
                callbackContext.error("Invalid channelId of " + channelId);
                return;
            }

            try {
                session = channel.getSession();
            } catch (JSchException e){

            } finally {
                channel.disconnect();

                if (session != null) {
                    session.disconnect();
                }
            }
        } else {
            FTPClient ftpClient = mFtpChannels.get(channelId);

            if (ftpClient == null) {
                callbackContext.error("Invalid channelId of " + channelId);
                return;
            }

            try {
                ftpClient.logout();
                ftpClient.disconnect();
            } catch (IOException e){

            }
        }

        callbackContext.success();
    }

    public void put(final JSONObject args, final IMoverInterface callbackContext) {
        commandHelper(new SftpOp() {
            @Override
            public void run(ChannelSftp channel, FTPClient ftpClient, JSONObject args) throws Exception {
                try {
                    String name = args.getString("name");
                    JSONObject dataContainer = args.getJSONObject("dataContainer");
                    boolean ensurePath = args.getBoolean("ensurePath");

                    if (ensurePath) {
                        _ensurePath(channel, ftpClient, name, true);
                    }

                    String type = dataContainer.getString("type");

                    Log.w("Wimsy", "Putting file " + name + " of type " + type);

                    OutputStream output;

                    if (channel != null) {
                        output = channel.put(name);
                    } else {
                        output = ftpClient.storeFileStream(name);
                    }

                    byte[] dataByteArray;

                    if (type.equals("url")) {
                        String url = dataContainer.getString("data");

                        if (dataContainer.getString("data").startsWith("file://")) {
                            // Strips the file:// prefix
                            url = url.substring(7);
                        } else if (dataContainer.getString("data").startsWith("file:")) {
                            // Strips the file: prefix
                            url = url.substring(5);
                        }

                        File file = new File(url);
                        dataByteArray = new byte[(int) file.length()];
                        DataInputStream dis = new DataInputStream(new FileInputStream(file));
                        dis.readFully(dataByteArray);
                        dis.close();
                    } else {
                        String data = dataContainer.getString("data");
                        dataByteArray = data.getBytes();
                    }

                    Log.w("alto", "writing");
                    output.write(dataByteArray);
                    Log.w("alto", "closing");
                    output.flush();
                    output.close();

                    if (ftpClient != null && !ftpClient.completePendingCommand()) {
                        callbackContext.error("Could not write file" + name);
                    }

                    Log.w("alto", "send success");

                    if (ftpClient != null) {
                        ftpClient.site("CHMOD 744 " + name);
                    }

                    if (channel != null) {
                        callbackContext.success();
                    } else {
                        callbackContext.success();
                    }

                } catch (Exception e) {
                    Log.e("alto", e.getClass().getName() + ":" + e.getMessage());
                    e.printStackTrace();
                    Log.e("alto", Arrays.toString(e.getStackTrace()));
                    callbackContext.error(e.getMessage());
                }
                Log.w("alto", "return");
            }
        }, args, callbackContext);

    }

    public void rm(final JSONObject args, final IMoverInterface callbackContext) {
        commandHelper(new SftpOp() {
            @Override
            public void run(ChannelSftp channel, FTPClient ftpClient, JSONObject args) throws JSONException {
                String name = args.getString("name");
                Log.w("Wimsy", "Deleting file " + name);

                try {
                    if (channel != null) {
                        channel.rm(name);
                    } else {
                        ftpClient.deleteFile(name);
                    }
                    callbackContext.success();
                } catch (Exception e) {
                    callbackContext.success("File not found: " + name);
                }

            }
        }, args, callbackContext);

    }

    public void rmdir(final JSONObject args, final IMoverInterface callbackContext) {
        commandHelper(new SftpOp() {
            @Override
            public void run(ChannelSftp channel, FTPClient ftpClient, JSONObject args) throws JSONException {
                String name = args.getString("name");
                Log.w("Wimsy", "Deleting folder " + name);

                try {
                    if (channel != null) {
                        channel.rmdir(name);
                    } else {
                        ftpClient.rmd(name);
                    }

                    callbackContext.success();
                } catch (Exception e) {
                    callbackContext.success("File not found: " + name);
                }
            }
        }, args, callbackContext);

    }

    public void ls(final JSONObject args, final IMoverInterface callbackContext) {
        commandHelper(new SftpOp() {
            @Override
            public void run(ChannelSftp channel, FTPClient ftpClient, JSONObject args) throws JSONException {
                String name = args.getString("name");
                Log.w("Wimsy", "Listing directory " + name);
                ArrayList<String> folderNamesTemp = new ArrayList<String>();
                String[] folderNames;

                try {
                    if (channel != null) {
                        Vector<ChannelSftp.LsEntry> entries = channel.ls(name);
                        for(ChannelSftp.LsEntry entry: entries) {
                            SftpATTRS attrs = entry.getAttrs();
                            if (attrs.isDir()) {
                              folderNamesTemp.add(entry.getFilename());
                            }
                        }
                    } else {
                        ftpClient.listDirectories(name);
                        FTPFile[] entries = ftpClient.listDirectories(name);
                        for(FTPFile entry: entries) {
                            folderNamesTemp.add(entry.getName());
                        }
                    }

                    folderNames = new String[folderNamesTemp.size()];
                    folderNamesTemp.toArray(folderNames);
                    Arrays.sort(folderNames);
                    ArrayList<HashMap<String, String>> folderNameMaps = new ArrayList<HashMap<String, String>>();

                    HashMap<String, String> folderNameMap = new HashMap<String, String>();
                    folderNameMap.put("name", "..");
                    folderNameMap.put("path", name + "/..");
                    folderNameMaps.add(folderNameMap);

                    for (String filename: folderNames) {
                        if (filename.equals(".") || filename.equals("..")) {
                          continue;
                        }
                        folderNameMap = new HashMap<String, String>();
                        folderNameMap.put("name", filename);
                        folderNameMap.put("path", name + "/" + filename);
                        folderNameMaps.add(folderNameMap);
                    }

                    callbackContext.success(folderNameMaps);
                } catch (Exception e) {
                    callbackContext.success(e.getMessage());
                }
            }
        }, args, callbackContext);

    }

    private void _ensurePath(ChannelSftp channel, FTPClient ftpClient, String path, boolean excludeLast) throws Exception {
        String buildPath = "";
        if (path.substring(0, 1).equals("/")) {
            path = path.substring(1);
            buildPath += "/";
        }

        String[] pathSegments = path.split("/");
        int size = pathSegments.length;

        if (excludeLast) {
            size -= 1;
        }

        for (int i=0; i<size; i++) {
            buildPath += pathSegments[i] + "/";

            if (channel != null) {
                try {
                    channel.stat(buildPath);
                } catch (Exception e) {
                    channel.mkdir(buildPath);
                }
            } else {
                ftpClient.makeDirectory(buildPath);
                ftpClient.site("CHMOD 755 " + buildPath);
            }

        }
    }

    private void commandHelper(final SftpOp f, final JSONObject args, final IMoverInterface callbackContext) {
        try {
            String protocol = args.getString("protocol");
            String cacheKey = args.getString("key");

            if (protocol.equals("SFTP")) {
                ChannelSftp channel = mSftpChannels.get(cacheKey);

                if (channel == null) {
                    callbackContext.error("Invalid channelId");
                    return;
                }

                synchronized (mSftpChannels) {
                    Log.w("alto", "Starting");
                    f.run(channel, null, args);
                    Log.w("alto", "Done");
                }
            } else {
                FTPClient client = mFtpChannels.get(cacheKey);

                if (client == null) {
                    callbackContext.error("Invalid clientId");
                    return;
                }

                synchronized (mFtpChannels) {
                    Log.w("alto", "Starting");
                    f.run(null, client, args);
                    Log.w("alto", "Done");
                }
            }

        } catch ( Exception e) {
            if (e instanceof JSONException ) {
                callbackContext.error("JSON Exception");
            } else {
                e.printStackTrace();
                callbackContext.error(e.getMessage());
            }
        }
    }
}
