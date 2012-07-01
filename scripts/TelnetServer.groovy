/*
* ---------------------------------------------------------
* Simple Vert.x Telnet Server
* ---------------------------------------------------------
* 
* Author: Bruno Bonacci
*
* This is just a simple example of a Telnet server implemented with Vert.x and Groovy.
* The purpose of this code is to demonstrate some the Vert.x capabilities, as well as
* its flexibility and power.
* 
*
* Copyright 2012 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

// Regular expression to verify/extract pathnames from commands
PATH_ELEM = "[A-Za-z0-9.-]+"
PATH = "(/?$PATH_ELEM(/$PATH_ELEM)*/?|/)"

// this is the base directory where all user will be segregated (like: "chroot")
BASE_DIR = new File(System.properties.baseDir ?: "/tmp").canonicalPath;

// This map maintains the session state for every connection.
// for this telnet server the state will be limited to the current directory
_connections = [:];

// Connection id counter
_cids = 0;

// general configuration
settings = [port: 1234];

// Vert.x server and event-bus
def server = vertx.createNetServer()
def bus = vertx.eventBus

// Connection handler. Here requests are captured, parsed, and executed.
server.connectHandler { sock ->
    // upon a new connection, assigning a new connection id (cid)
    String cid = registerConnection();
    println "[$cid]# got a new connection id: $cid - currently ${_connections.size()} are alive.";

    // welcome the user with a message
    sock.write(welcome())

    // handle the connection close.
    sock.closedHandler { _connections.remove(cid); println "[$cid]# The connection is now closed" }

    // handle incoming requests from a single connection
    // a request, might contains multiple commands, splitting them by line.
    sock.dataHandler { buffer ->
        def lines = new String(buffer.bytes).trim().split("\r\n").collect { it.trim() }
        lines.each {
            println "[$cid]> $it"
            if (it == "") return

            // if a command is found send it to the commands-handler
            bus.send("commands", [command: it, cid: cid]) {
                resp ->
                // once you get a response for a command
                // then send it back to the user
                String outMessage = prepareOutput(resp.body.message)
                if (outMessage != "") {
                    sock.write("$outMessage\r\n") {
                        println "[$cid]< " + outMessage.replaceAll("\n", "\n[$cid]< ")
                    }
                }

                // if the command was "quit", close the connection.
                if (resp.body.status == "ACTION" && resp.body.action == "QUIT")
                    sock.close()
            }
        }
    }
}.listen( settings.port );

println """
---------------------------------------------------------------------
                   Simple Vert.x Telnet Server
---------------------------------------------------------------------
(*) Server started at $BASE_DIR and listening on port: $settings.port
"""

// assigning an id to a new connection
def registerConnection() {
    String cid = "${++_cids}"
    _connections[cid] = [curDir: BASE_DIR]
    return cid;
}

// Commands handler
bus.registerHandler("commands") {
    msg ->
    def cmd = msg.body.command
    switch (cmd) {
        case ~$/cd( $PATH)?/$:
            def path = cmd.size() > 3 ? cmd[3..-1] : ""
            msg.reply(cd(path, msg.body.cid));
            break;
        case ~$/dir( $PATH)?/$:
            def path = cmd.size() > 4 ? cmd[4..-1] : ""
            msg.reply(ls(path, msg.body.cid));
            break;
        case ~$/ls( $PATH)?/$:
            def path = cmd.size() > 3 ? cmd[3..-1] : ""
            msg.reply(ls(path, msg.body.cid));
            break;
        case ~$/cat $PATH/$:
            def path = cmd[4..-1]
            msg.reply(cat(path, msg.body.cid));
            break;
        case ~$/touch $PATH/$:
            def path = cmd[6..-1]
            msg.reply(touch(path, msg.body.cid));
            break;
        case ~$/pwd/$:
            msg.reply(pwd(msg.body.cid));
            break;
        case ~$/mkdir $PATH/$:
            def path = cmd[6..-1]
            msg.reply(mkdir(path, msg.body.cid));
            break;
        case ~$/help/$:
        case ~$/\?/$:
            msg.reply(help());
            break;
        case ~$/quit/$:
            msg.reply([status: "ACTION", action: "QUIT", message: "Goodbye!!!"]);
            break;
        default:
            msg.reply([status: "ERROR", message: "### ERROR: Sorry, this command is unrecognized!"]);
    }
}

////////////////////////////////////////////////////////////////////////// cd
def cd(def path, String cid) {
    path = path == "" ? null : path
    def newPath = virtual2real(path, cid);

    if (!newPath.exists() || !newPath.isDirectory())
        return [status: "ERROR", message: "### ERROR: $path: no such directory!".toString()]

    // changing directory
    _connections[cid].curDir = new File(newPath.canonicalPath);

    return [status: "OK", message: ""];
}

////////////////////////////////////////////////////////////////////////// ls
def ls(def path, String cid) {
    path = path == "" ? "." : path
    def newPath = virtual2real(path, cid);

    if (!newPath.exists() || !newPath.isDirectory())
        return [status: "ERROR", message: "### ERROR: $path: no such directory!".toString()]

    def files = newPath.listFiles();
    String content = "Files present: ${files.size()}\n"
    content += files.sort().collect {
        f ->
        String.format('%1$tF %1$tT  %2$10d  %3$s', new Date(f.lastModified()), f.length(), "$f.name" + (f.isDirectory() ? "/" : ""))
    }.join("\n")

    return [status: "OK", message: content];
}

////////////////////////////////////////////////////////////////////////// cat
def cat(def path, String cid) {
    def newPath = virtual2real(path, cid);

    if (!newPath.exists() || !newPath.isFile())
        return [status: "ERROR", message: "### ERROR: $path: no such file!".toString()]

    String content = newPath.text;

    return [status: "OK", message: content];
}

////////////////////////////////////////////////////////////////////////// touch
def touch(def path, String cid) {
    def newPath = virtual2real(path, cid);

    if (newPath.isDirectory())
        return [status: "ERROR", message: "### ERROR: $path: directories can't be touched!".toString()]

    if (!newPath.exists())
        newPath.text = '';
    else
        newPath.setLastModified(System.currentTimeMillis())

    return [status: "OK", message: ""];
}

////////////////////////////////////////////////////////////////////////// pwd
def pwd(String cid) {
    return [status: "OK", message: real2virtual(_connections[cid].curDir)];
}

////////////////////////////////////////////////////////////////////////// mkdir
def mkdir(def path, String cid) {
    path = path == "" ? null : path
    def newPath = virtual2real(path, cid);

    if (newPath.exists())
        return [status: "ERROR", message: "### ERROR: $path: alredy exists!".toString()]

    // creating directories
    if (!newPath.mkdirs())
        return [status: "ERROR", message: "### ERROR: Unable to create directories $path".toString()]

    return [status: "OK", message: ""];
}


////////////////////////////////////////////////////////////////////////// help
def help() {
[status: "OK", message:'''
#        Simple Vert.x Telnet Server.
# -------------------------------------------
# Available commands:
#
# cd [<path>]  - change the current directory
# pwd          - prints out the current directory
# mkdir <path> - create new sub-directories
# ls [<path>]  - displays the content of a directory
# dir [<path>] - same as ls
# touch <file> - create an empty file or change
#                the timestamp modification for
#                an existing file.
# cat <file>   - display the content of a file
# help         - display this screen
# quit         - to close the current session
#
'''.replaceAll("\n", "\r\n") ]
}


////////////////////////////////////////////////////////////////////////// utility
////////////////////////////////////////////////////////////////////////// methods

// this function takes a real path and convert it into a telnet user path
String real2virtual(def path) {
    String pathname = new File("$path").canonicalPath - BASE_DIR;
    return pathname == "" ? "/" : pathname
}

// this function takes a telnet user path and convert it into a real fs path
// additionally it takes care of security check to avoid path tampering.
File virtual2real(def path, def cid) {
    def newPath = new File(BASE_DIR);

    if (path != null) {
        if (path.startsWith("/"))
            newPath = new File("$BASE_DIR$path");
        else
            newPath = new File("${_connections[cid].curDir}/$path");
    }

    // security: in case goes above root like: /../../
    if (!newPath.canonicalPath.startsWith(BASE_DIR))
        newPath = new File(BASE_DIR);

    return newPath;
}

// this function clean the output and replace new lines characters with
// the standard telnet newline \r\n
String prepareOutput(String message) {
    String outMessage = message?.trim() ?: "";
    outMessage = "$outMessage".replaceAll("\n", "\r\n");
    return outMessage
}

// welcome message
String welcome() {
'''
# Welcome to the Simple Vert.x Telnet Server.
# -------------------------------------------
#
# type 'help' or '?' for a list
#   - of available commands
# type 'quit' to exit.
# 
'''.replaceAll("\n", "\r\n")
}
