
for (entry in System.getProperties().entries)
    println("${entry.key} = ${entry.value}")
println("args = ${args.joinToString(" ")}")
