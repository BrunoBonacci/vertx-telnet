vertx-telnet - Simple Vert.x Telnet Server
==========================================

This is a simple Telnet Server (NOT compliant to RFC-854), it is intended to show
the simplicity and power of Vert.x platform.

READ THE FULL ARTICLE: http://brunobonacci.wordpress.com/2012/06/30/vertx-simple-telnet-server

Features:
--------
 - accepts multiple users
 - chroot user restriction
 - implements few commands such as
   - ls / dir
   - cd
   - mkdir
   - pwd
   - cat
   - touch


How to run:
-----------
 - To run the server

    $ vertx run scripts/TelnetServer.groovy

 - To run the client open multiple console windows and type:

    $ telnet localhost 1234


