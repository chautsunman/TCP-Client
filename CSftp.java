
import java.lang.System;
import java.io.IOException;

import java.io.*;
import java.net.*;

import java.util.Arrays;

//
// This is an implementation of a simplified version of a command
// line ftp client. The program always takes two arguments
//


public class CSftp {
    static final int MAX_LEN = 255;
    static final int ARG_CNT = 2;

    static final String REQUEST_PREFIX = "--> ";
    static final String RESPONSE_PREFIX = "<-- ";

    // array of supported (valid) commands
    static final String[] validCommands = {
        "quit",
        "user",
        "pw",
        "cd",
        "dir",
        "get",
        "features"
    };

    public static void main(String [] args) {
        byte cmdString[] = new byte[MAX_LEN];
        String command;

        // Get command line arguments and connected to FTP
        // If the arguments are invalid or there aren't enough of them
            // then exit.

        if (args.length != 2 && args.length != 1) {
            System.out.print("Usage: cmd ServerAddress ServerPort\n");
            return;
        }

        String hostName = args[0];
        int hostPort = 21;
        if (args.length == 2) {
            hostPort = Integer.parseInt(args[1]);
        }

        try (
            // create a socket
            Socket socket = new Socket(hostName, hostPort);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        ) {
            // set socket timeout for reading responses
            socket.setSoTimeout(500);

            printResponse(in);

            for (int len = 1; len > 0;) {
                System.out.print("csftp> ");
                try {
                    len = System.in.read(cmdString);
                } catch (IOException exception) {
                    System.err.println("0xFFFE Input error while reading commands, terminating.");
                    closeSocket(socket);
                    System.exit(1);
                }

                command = (new String(Arrays.copyOfRange(cmdString, 0, len-1)));

                if (len <= 0)
                    break;

                // ignore empty lines and lines starting with "#"
                if (command.trim().length() == 0 || (command.length() > 0 && command.indexOf("#") == 0)) {
                    continue;
                }

                // Start processing the command here.
                if (command.equals("quit")) {
                    // close the connection
                    sendRequest(out, "QUIT", in);

                    // close the socket
                    closeSocket(socket);

                    // break the command input loop, exit the program
                    break;
                }

                // trim trailing whitespace
                command = command.replaceAll("\\s++$", "");

                String[] commandSplit = command.split("\\s+");
                int commandSplitLen = commandSplit.length;

                if (commandSplitLen == 2 && commandSplit[0].equals("user")) {
                    // user USERNAME
                    sendRequest(out, "USER " + commandSplit[1], in);
                } else if (commandSplitLen == 2 && commandSplit[0].equals("pw")) {
                    // pw PASSWORD
                    sendRequest(out, "PASS " + commandSplit[1], in);
                } else if (command.equals("features")) {
                    // features
                    sendRequest(out, "FEAT", in);
                } else if (commandSplitLen == 2 && commandSplit[0].equals("cd")) {
                    // cd DIRECTORY
                    sendRequest(out, "CWD " + commandSplit[1], in);
                } else if (command.equals("dir")) {
                    // dir
                    listDirectory(out, in);
                } else if (commandSplitLen == 2 && commandSplit[0].equals("get")) {
                    // get REMOTE
                    getFile(out, commandSplit[1], in);
                } else if (!isValidCommand(commandSplit[0])) {
                    // the command is not one of the supported commands
                    System.out.println("0x001 Invalid command.");
                } else {
                    // the command is one of the supported commands, but it does not match the usage pattern
                    System.out.println("0x002 Incorrect number of arguments");
                }
            }
        } catch (UnknownHostException e) {
            System.err.println("0xFFFC Control connection to " + hostName + " on port " + hostPort + " failed to open.");
            System.exit(1);
        } catch (IOException e) {
            System.err.println("0xFFFD Control connection I/O error, closing control connection.");
            System.exit(1);
        }
    }

    // check if the command is supported (valid)
    private static boolean isValidCommand(String command) {
        for (String validCommand : validCommands) {
            if (command.equals(validCommand)) {
                return true;
            }
        }

        return false;
    }

    private static void sendRequest(PrintWriter out, String command, BufferedReader in) {
        System.out.println(REQUEST_PREFIX + command);

        out.println(command);
        printResponse(in);
    }

    private static void printResponse(BufferedReader in) {
        try {
            String response;
            while ((response = in.readLine()) != null) {
                System.out.println(RESPONSE_PREFIX + response);
            }
        } catch (SocketTimeoutException e) {
            // stop waiting for more responses
        } catch (IOException e) {
            System.out.println("0xFFFF Processing error. Reading response IO error, terminating.");
            System.exit(1);
        }
    }

    private static void listDirectory(PrintWriter out, BufferedReader in) {
        // create the data connection
        if (DataConnection.createDataConnection(out, in)) {
            BufferedReader dataIn = DataConnection.getDataIn();

            // list all the files
            System.out.println(REQUEST_PREFIX + "LIST");
            out.println("LIST");

            // print all responses
            printResponse(in);
            printResponse(dataIn);

            // close the data connection
            DataConnection.closeDataConnection();
        } else {
            printResponse(in);
        }
    }

    private static void getFile(PrintWriter out, String fileName, BufferedReader in) {
        // create the data connection
        if (DataConnection.createDataConnection(out, in)) {
            InputStream dataInputStream = DataConnection.getDataInputStream();

            // get the file
            System.out.println(REQUEST_PREFIX + "RETR " + fileName);
            out.println("RETR " + fileName);

            boolean fileNotFound = false;

            try {
                String response = in.readLine();

                System.out.println(RESPONSE_PREFIX + response);

                if (response.substring(0, 3).equals("550")) {
                    fileNotFound = true;
                }
            } catch (SocketTimeoutException e) {
                // stop waiting for more responses
            } catch (IOException e) {
                System.out.println("0xFFFF Processing error. Reading response IO error, terminating.");
                System.exit(1);
            }

            // print all responses
            printResponse(in);

            // return if the file is not found on the server
            if (fileNotFound) {
                // close the data connection
                DataConnection.closeDataConnection();

                return;
            }

            // write the file
            FileOutputStream fileOutputStream = null;
            try {
                fileOutputStream = new FileOutputStream(fileName);

                int b;

                while ((b = dataInputStream.read()) != -1) {
                    fileOutputStream.write(b);
                }
            } catch (SocketTimeoutException e) {
                // stop waiting for more responses
                System.out.println("0xFFFF Processing error. File may not be completely retrieved, terminating.");
                System.exit(1);
            } catch (IOException e) {
                System.out.println("0x38E Access to local file " + fileName + " denied.");
            } finally {
                if (fileOutputStream != null) {
                    try {
                        fileOutputStream.close();
                    } catch (IOException e) {
                        System.out.println("0xFFFF Processing error. Cannot close the file output stream, terminating.");
                        System.exit(1);
                    }
                }
            }

            // print all remaining control responses
            printResponse(in);

            // close the data connection
            DataConnection.closeDataConnection();
        } else {
            printResponse(in);
        }
    }

    private static boolean closeSocket(Socket socket) {
        if (socket == null || socket.isClosed()) {
            return true;
        }

        try {
            socket.close();
        } catch (IOException e) {
            System.out.println("0xFFFF Processing error. Cannot close the socket, terminating.");
            System.exit(1);
        } finally {
            return socket.isClosed();
        }
    }


    private static class DataConnection {
        static Socket dataSocket;
        static InputStream dataInputStream;
        static BufferedReader dataIn;

        public static Socket getDataSocket() {
            return dataSocket;
        }

        public static InputStream getDataInputStream() {
            return dataInputStream;
        }

        public static BufferedReader getDataIn() {
            return dataIn;
        }

        public static boolean createDataConnection(PrintWriter out, BufferedReader in) {
            DataConnectionData dataConnectionData = getDataConnectionData(out, in);

            if (dataConnectionData != null) {
                String host = dataConnectionData.getHost();
                int port = dataConnectionData.getPort();

                if (host == null || port == -1) {
                    return false;
                }

                try {
                    dataSocket = new Socket(host, port);
                    dataSocket.setSoTimeout(500);
                    dataInputStream = dataSocket.getInputStream();
                    dataIn = new BufferedReader(new InputStreamReader(dataInputStream));
                } catch (UnknownHostException e) {
                    System.out.println("0x3A2 Data transfer connection to " + host + " on port " + port + " failed to open.");
                    System.exit(1);
                } catch (IOException e) {
                    System.err.println("0x3A7 Data transfer connection I/O error, closing data connection.");
                    System.exit(1);
                }

                return true;
            }

            return false;
        }

        private static DataConnectionData getDataConnectionData(PrintWriter out, BufferedReader in) {
            System.out.println(REQUEST_PREFIX + "PASV");

            out.println("PASV");

            try {
                String response = in.readLine();

                System.out.println(RESPONSE_PREFIX + response);

                return new DataConnectionData(response);
            } catch (SocketTimeoutException e) {
                // stop waiting for responses
                return null;
            } catch (IOException e) {
                System.out.println("0xFFFF Processing error. Reading response IO error, terminating.");
                System.exit(1);
            }

            return null;
        }

        private static boolean closeDataConnection() {
            if (dataSocket == null || dataSocket.isClosed()) {
                return true;
            }

            try {
                dataSocket.close();
            } catch (IOException e) {
                System.out.println("0xFFFF Processing error. Cannot close the socket, terminating.");
                System.exit(1);
            } finally {
                boolean socketClosed = dataSocket.isClosed();

                dataSocket = null;
                dataInputStream = null;
                dataIn = null;

                return socketClosed;
            }
        }


        private static class DataConnectionData {
            String host;
            int port;

            public DataConnectionData(String passiveRequestResponse) {
                parseResponse(passiveRequestResponse);
            }

            public String getHost() {
                return host;
            }

            public int getPort() {
                return port;
            }

            private void parseResponse(String response) {
                int openParenthesisIndex = response.indexOf("(");
                int closingParenthesisIndex = response.indexOf(")");

                if (openParenthesisIndex != -1 && closingParenthesisIndex != -1) {
                    String[] hostsPortsNumbers = response.substring(openParenthesisIndex+1, closingParenthesisIndex).split(",");

                    host = hostsPortsNumbers[0] + "." + hostsPortsNumbers[1] + "." + hostsPortsNumbers[2] + "." + hostsPortsNumbers[3];
                    port = Integer.parseInt(hostsPortsNumbers[4]) * 256 + Integer.parseInt(hostsPortsNumbers[5]);
                } else {
                    host = null;
                    port = -1;
                }
            }
        }
    }
}
