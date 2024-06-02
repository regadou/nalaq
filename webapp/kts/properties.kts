val props = mutableListOf<String>()
for (entry in System.getProperties().entries)
    props.add("${entry.key} = ${entry.value}")
println("Sending ${props.size} properties to display in the web client ...")
"<pre>"+props.sorted().joinToString("\n")+"</pre>"
