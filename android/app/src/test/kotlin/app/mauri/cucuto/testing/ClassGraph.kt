package app.mauri.cucuto.testing

import java.io.DataInputStream
import java.io.File
import java.net.JarURLConnection
import java.net.URL
import java.util.ArrayDeque

/**
 * A minimal reader of compiled `.class` files: enough of the constant pool to tell which classes, members and string
 * constants a class refers to, and to walk the static reference graph of our own code. No bytecode library needed.
 */
internal class ClassGraph(
    private val loader: ClassLoader = ClassGraph::class.java.classLoader!!,
    /** Internal-name prefix of the classes [reachableFrom] follows (others are leaves). */
    private val ownPrefix: String = "app/mauri/cucuto/",
) {
    /** What one class refers to. Names are internal names (`android/os/VibrationEffect$Builder`). */
    data class ClassRefs(
        val name: String,
        val classes: Set<String>,
        /** `owner.name` for every field and method reference. */
        val members: Set<String>,
        val strings: Set<String>,
    )

    private val cache = HashMap<String, ClassRefs?>()

    /** The references of class [name], or null when it is not on the classpath. */
    fun refs(name: String): ClassRefs? =
        cache.getOrPut(name) { loader.getResourceAsStream("$name.class")?.use(::parse) }

    /**
     * Every class with [ownPrefix] reachable from [seeds] through any reference (a conservative superset of what
     * can load at runtime), mapped to the class that first referred to it (null for the seeds).
     */
    fun reachableFrom(seeds: Collection<String>): Map<String, String?> {
        val parent = LinkedHashMap<String, String?>()
        val queue = ArrayDeque<String>()
        for (seed in seeds) {
            if (seed !in parent) {
                parent[seed] = null
                queue += seed
            }
        }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val refs = refs(current) ?: continue
            for (next in refs.classes) {
                if (next.startsWith(ownPrefix) && next !in parent) {
                    parent[next] = current
                    queue += next
                }
            }
        }
        return parent
    }

    /** The path from a seed to [name] in a [reachableFrom] result, for readable failures. */
    fun pathTo(
        name: String,
        parents: Map<String, String?>,
    ): List<String> = generateSequence(name) { parents[it] }.toList().reversed()

    /** Internal names of every class in the classpath root (directory or jar) that holds [anyClass]. */
    fun classesBeside(anyClass: String): List<String> {
        val url: URL = loader.getResource("$anyClass.class") ?: return emptyList()
        return when (url.protocol) {
            "file" -> {
                val root = File(url.toURI()).path.removeSuffix("$anyClass.class".replace('/', File.separatorChar))
                File(root)
                    .walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".class") }
                    .map { it.relativeTo(File(root)).invariantSeparatorsPath.removeSuffix(".class") }
                    .toList()
            }

            "jar" -> {
                (url.openConnection() as JarURLConnection).jarFile.use { jar ->
                    jar
                        .entries()
                        .asSequence()
                        .filter { it.name.endsWith(".class") }
                        .map { it.name.removeSuffix(".class") }
                        .toList()
                }
            }

            else -> {
                emptyList()
            }
        }
    }

    private fun parse(stream: java.io.InputStream): ClassRefs {
        val input = DataInputStream(stream.buffered())
        require(input.readInt() == MAGIC) { "Not a class file" }
        input.readUnsignedShort() // minor
        input.readUnsignedShort() // major
        val count = input.readUnsignedShort()
        val utf8 = arrayOfNulls<String>(count)
        val classIndex = HashMap<Int, Int>()
        val stringIndex = ArrayList<Int>()
        val memberRefs = ArrayList<Pair<Int, Int>>() // (class index, name-and-type index)
        val nameAndType = HashMap<Int, Int>() // index -> name index
        var i = 1
        while (i < count) {
            when (val tag = input.readUnsignedByte()) {
                1 -> {
                    utf8[i] = input.readUTF()
                }

                3, 4 -> {
                    input.readInt()
                }

                5, 6 -> {
                    input.readLong()
                    i++
                }

                7 -> {
                    classIndex[i] = input.readUnsignedShort()
                }

                8 -> {
                    stringIndex += input.readUnsignedShort()
                }

                9, 10, 11 -> {
                    memberRefs += input.readUnsignedShort() to input.readUnsignedShort()
                }

                12 -> {
                    nameAndType[i] = input.readUnsignedShort()
                    input.readUnsignedShort()
                }

                15 -> {
                    input.readUnsignedByte()
                    input.readUnsignedShort()
                }

                16, 19, 20 -> {
                    input.readUnsignedShort()
                }

                17, 18 -> {
                    input.readUnsignedShort()
                    input.readUnsignedShort()
                }

                else -> {
                    error("Unknown constant pool tag $tag")
                }
            }
            i++
        }
        input.readUnsignedShort() // access flags
        val thisClass = utf8[classIndex.getValue(input.readUnsignedShort())]!!

        val classes = HashSet<String>()
        for (nameIndex in classIndex.values) {
            val name = utf8[nameIndex] ?: continue
            if (name.startsWith("[")) {
                DESCRIPTOR_CLASS.findAll(name).forEach { classes += it.groupValues[1] }
            } else {
                classes +=
                    name
            }
        }
        // Classes that appear only in descriptors and signatures (parameter, return and field types).
        for (value in utf8) {
            if (value != null &&
                value.contains(';')
            ) {
                DESCRIPTOR_CLASS.findAll(value).forEach { classes += it.groupValues[1] }
            }
        }
        classes -= thisClass
        val members =
            memberRefs.mapNotNullTo(HashSet()) { (owner, nat) ->
                val ownerName = classIndex[owner]?.let { utf8[it] } ?: return@mapNotNullTo null
                val memberName = nameAndType[nat]?.let { utf8[it] } ?: return@mapNotNullTo null
                "$ownerName.$memberName"
            }
        val strings = stringIndex.mapNotNullTo(HashSet()) { utf8[it] }
        return ClassRefs(thisClass, classes, members, strings)
    }

    private companion object {
        const val MAGIC = 0xCAFEBABE.toInt()
        val DESCRIPTOR_CLASS = Regex("L([A-Za-z0-9_/$]+)[;<]")
    }
}
