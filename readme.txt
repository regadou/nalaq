Welcome to the NaLaQ system open source code

- The system is programmed with Kotlin 1.9 and runs on the JVM
- To compile and run, just do ./run.sh in a terminal from this directory:
  - without arguments, it will start a command line eval loop
  - if the only argument is an integer, it will start the HTTP server listening at the port number specified as the argument
  - if the first argument is either conf, config or configuration:
    - the second argument is used as an uri to load configuration data
    - if no more arguments, it will start the eval loop after applying the specified configuration
  - else the rest or the arguments are concatenated into NaLaQ expressions (one expression per line)
- You can execute ./install.sh to have the nalaq command available in your path (default is /usr/local/bin)
- For testing examples, see scripts/tests.sh
- To test the NaLaQ API with kotlin, run scripts/kotlin.sh
- For more usage examples, see the scripts folder
- You will need to run the install script before running examples in the scripts folder
- Beware that the scripts are meant to be run from the NaLaQ root folder
- For the design concepts behind the system, see doc/nalaq-system.txt
- For a summary of expected REST API behavior, see doc/rest-api.xlsx
- You can find some data to work with in the webapp/json folder
- This is not ready for production !!!
  - Do not install this application on a public server
  - Only install it on a local server that do not bind to a public IP address
  - There are a lot of security issues with this code
  - Your server WILL BE HACKED if you start it on the public internet
  - You have been warned !!!
 
