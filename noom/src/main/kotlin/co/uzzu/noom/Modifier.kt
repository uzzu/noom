package co.uzzu.noom

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.apache.bcel.Const
import org.apache.bcel.classfile.ClassParser
import org.apache.bcel.classfile.InnerClass
import org.apache.bcel.classfile.InnerClasses
import org.apache.bcel.classfile.JavaClass
import org.apache.bcel.classfile.Method
import org.apache.bcel.generic.ALOAD
import org.apache.bcel.generic.BIPUSH
import org.apache.bcel.generic.ClassGen
import org.apache.bcel.generic.GETFIELD
import org.apache.bcel.generic.GOTO
import org.apache.bcel.generic.ICONST
import org.apache.bcel.generic.IF_ICMPLT
import org.apache.bcel.generic.INVOKEVIRTUAL
import org.apache.bcel.generic.MethodGen
import org.apache.bcel.generic.POP
import kotlin.experimental.or

private const val ClassesJarPrefix = "classes"
private const val ClassesJarSuffix = ".jar"
private const val ClassesJarFileName = "$ClassesJarPrefix$ClassesJarSuffix"
private const val ListenerClassName = "com/google/android/play/core/listener/b.class"

private val ListenerFuncPredicate: (Method) -> Boolean = {
    it.isPrivate &&
        it.isFinal &&
        it.name == "b" &&
        it.signature == "()V"
}

class PlayCore34Modifier(
    private val config: Configuration,
) {

    data class Configuration(
        val input: File,
        val output: File,
    )

    fun modify() {
        val outputJar = File.createTempFile(ClassesJarPrefix, ClassesJarSuffix)
        config.input.useAsZipFile {
            val classesJarEntry = classesJarEntry()
            val classesJarFile = getInputStream(classesJarEntry).use { input ->
                val tempFile = File.createTempFile("tmp", ".jar")
                FileOutputStream(tempFile).use { output ->
                    output.write(input.readBytes())
                }
                tempFile
            }
            classesJarFile.useAsJarFile {
                val listenerClass = listenerClass()
                val listenerFunc = listenerClass.findListenerFunc()
                val listenerClassGen = ClassGen(listenerClass)
                val constantPoolGen = listenerClassGen.constantPool
                val listenerFuncGen = MethodGen(listenerFunc, listenerClassGen.className, constantPoolGen)

                // Add Context#registerReceiver method to constant pool
                val registerReceiverNameAndTypeIndex = constantPoolGen.addNameAndType("registerReceiver", "(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;I)Landroid/content/Intent;")
                check(registerReceiverNameAndTypeIndex != -1)
                val registerReceiverIndex = constantPoolGen.addMethodref("android/content/Context", "registerReceiver", "(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;I)Landroid/content/Intent;")
                check(registerReceiverIndex != -1)

                // common access flags for inner class
                val accessFlagRaw = Const.ACC_PUBLIC or Const.ACC_STATIC

                // Add android.os.Build class to constant pool
                val buildIndex = constantPoolGen.addClass("android/os/Build")
                check(buildIndex != -1)
                val innerClassesNameIndex = constantPoolGen.addUtf8("InnerClasses")
                check(buildIndex != -1)

                // Add Build.VERSION.SDK_INT to constant pool
                val versionIndex = constantPoolGen.addClass("android/os/Build\$VERSION")
                check(versionIndex != -1)
                val versionNameIndex = constantPoolGen.addUtf8("VERSION")
                check(versionNameIndex != -1)
                val sdkIntIndex = constantPoolGen.addFieldref("android/os/Build\$VERSION", "SDK_INT", "I")
                check(sdkIntIndex != -1)
                val versionInnerClass = InnerClass(versionIndex, buildIndex, versionNameIndex, accessFlagRaw.toInt())

                // constantPoolGen.constantPool.forEachIndexed {index, constant ->
                //     println("$index, $constant")
                // }

                // Add Build.VERSION_CODES into constant pool
                val versionCodeIndex = constantPoolGen.addClass("android/os/Build\$VERSION_CODES")
                check(versionCodeIndex != -1)
                val versionCodeNameIndex = constantPoolGen.addUtf8("VERSION_CODES")
                check(versionCodeNameIndex != -1)
                val versionCodeInnerClass = InnerClass(versionCodeIndex, buildIndex, versionCodeNameIndex, accessFlagRaw.toInt())

                // Add InnerClasses attributes
                val innerClassArray = arrayOf(versionInnerClass, versionCodeInnerClass)
                val innerClassesAttribute = InnerClasses(innerClassesNameIndex, innerClassArray.size, innerClassArray, constantPoolGen.constantPool)
                listenerClassGen.addAttribute(innerClassesAttribute)

                listenerFuncGen.instructionList.forEachIndexed { index, handle ->
                    println("${handle.position} ${handle.instruction}")
                }

                // Remove instructions for calling android/content/Context.registerReceiver:(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;)Landroid/content/Intent;
                val originalInstructionList = listenerFuncGen.instructionList.toList()
                val deleteFrom = originalInstructionList[18] // aload_0[42](1)
                val deleteTo = originalInstructionList[25] // pop[87]
                val next = originalInstructionList[26] // aload_0[42](1)
                listenerFuncGen.instructionList.delete(deleteFrom, deleteTo)

                // Add instruction for else statement to last
                // play.core 1.1.0
                //     40: aload_0
                //     41: getfield      #46                 // Field d:Landroid/content/Context;
                //     44: aload_0
                //     45: getfield      #33                 // Field e:Lcom/google/android/play/core/listener/a;
                //     48: aload_0
                //     49: getfield      #39                 // Field c:Landroid/content/IntentFilter;
                //     52: invokevirtual #126                // Method android/content/Context.registerReceiver:(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;)Landroid/content/Intent;
                //     55: pop
                // pad 2.1.0
                //     107: aload_0
                //     108: getfield      #44                 // Field d:Landroid/content/Context;
                //     111: aload_0
                //     112: getfield      #31                 // Field e:Lcom/google/android/play/core/assetpacks/internal/m;
                //     115: aload_0
                //     116: getfield      #37                 // Field c:Landroid/content/IntentFilter;
                //     119: invokevirtual #136                // Method android/content/Context.registerReceiver:(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;)Landroid/content/Intent;
                //     122: pop
                //     123: goto          64
                val elseHandle = listenerFuncGen.instructionList.append(ALOAD(0))
                listenerFuncGen.instructionList.append(GETFIELD(46))
                listenerFuncGen.instructionList.append(ALOAD(0))
                listenerFuncGen.instructionList.append(GETFIELD(33))
                listenerFuncGen.instructionList.append(ALOAD(0))
                listenerFuncGen.instructionList.append(GETFIELD(39))
                listenerFuncGen.instructionList.append(INVOKEVIRTUAL(126))
                listenerFuncGen.instructionList.append(POP())
                listenerFuncGen.instructionList.append(GOTO(next))
                listenerFuncGen.instructionList.setPositions(false)

                // Add if instruction to deleted position
                // play.core 1.1.0
                //     40: aload_0
                //     41: getfield      #46                 // Field d:Landroid/content/Context;
                //     44: aload_0
                //     45: getfield      #33                 // Field e:Lcom/google/android/play/core/listener/a;
                //     48: aload_0
                //     49: getfield      #39                 // Field c:Landroid/content/IntentFilter;
                //     52: invokevirtual #126                // Method android/content/Context.registerReceiver:(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;)Landroid/content/Intent;
                //     55: pop
                // pad 2.1.0
                //     39: getstatic     #123                // Field android/os/Build$VERSION.SDK_INT:I
                //     42: bipush        33
                //     44: if_icmplt     107
                //     47: aload_0
                //     48: getfield      #44                 // Field d:Landroid/content/Context;
                //     51: aload_0
                //     52: getfield      #31                 // Field e:Lcom/google/android/play/core/assetpacks/internal/m;
                //     55: aload_0
                //     56: getfield      #37                 // Field c:Landroid/content/IntentFilter;
                //     59: iconst_2
                //     60: invokevirtual #129                // Method android/content/Context.registerReceiver:(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;I)Landroid/content/Intent;
                //     63: pop
                listenerFuncGen.instructionList.insert(next, GETFIELD(sdkIntIndex))
                listenerFuncGen.instructionList.insert(next, BIPUSH(33.toByte()))
                listenerFuncGen.instructionList.insert(next, IF_ICMPLT(elseHandle))
                listenerFuncGen.instructionList.insert(next, ALOAD(0))
                listenerFuncGen.instructionList.insert(next, GETFIELD(46))
                listenerFuncGen.instructionList.insert(next, ALOAD(0))
                listenerFuncGen.instructionList.insert(next, GETFIELD(33))
                listenerFuncGen.instructionList.insert(next, ALOAD(0))
                listenerFuncGen.instructionList.insert(next, GETFIELD(39))
                listenerFuncGen.instructionList.insert(next, ICONST(2))
                listenerFuncGen.instructionList.insert(next, INVOKEVIRTUAL(registerReceiverIndex))
                listenerFuncGen.instructionList.insert(next, POP())
                listenerFuncGen.instructionList.setPositions(true)

                //  for (i in 0 .. originalInstructionList.lastIndex) {
                //      val original = originalInstructionList[i]
                //      val modified = listenerFuncGen.instructionList.toList()[i]
                //      println("${original.position} ${original.instruction} \t\t | ${modified.position} ${modified.instruction}")
                //  }

                listenerFuncGen.setMaxStack()
                listenerFuncGen.setMaxLocals()

                listenerClassGen.removeMethod(listenerFunc)
                listenerClassGen.addMethod(listenerFuncGen.method)

                val modifiedListenerClass = listenerClassGen.javaClass
                modifiedListenerClass.fileName = ListenerClassName

                JarOutputStream(FileOutputStream(outputJar)).use { outputStream ->
                    entries().asSequence().forEach { entry ->
                        if (entry.name == ListenerClassName) {
                            val jarEntry = JarEntry(ListenerClassName)
                            outputStream.putNextEntry(jarEntry)
                            val bytes = ByteArrayOutputStream().use {
                                modifiedListenerClass.dump(it)
                                it.toByteArray()
                            }
                            outputStream.write(bytes)
                        } else {
                            outputStream.putNextEntry(entry)
                            getInputStream(entry).use { inputStream ->
                                outputStream.write(inputStream.readBytes())
                            }
                        }
                        outputStream.closeEntry()
                    }
                }
            }

            ZipOutputStream(FileOutputStream(config.output)).use { outputStream ->
                entries().asSequence().forEach {  entry ->
                    if (entry.name == ClassesJarFileName) {
                        val jarEntry = JarEntry(ClassesJarFileName)
                        outputStream.putNextEntry(jarEntry)
                        val bytes = outputJar.readBytes()
                        outputStream.write(bytes)
                    } else {
                        outputStream.putNextEntry(entry)
                        getInputStream(entry).use { inputStream ->
                            outputStream.write(inputStream.readBytes())
                        }
                    }
                    outputStream.closeEntry()
                }
            }
        }
    }

    private fun <T> File.useAsZipFile(block: ZipFile.() -> T): T =
        ZipFile(this).use {
            block(it)
        }

    private fun ZipFile.classesJarEntry(): ZipEntry =
        checkNotNull(
            entries().asSequence().find { entry -> entry.name == ClassesJarFileName }
        )

    private fun JarFile.listenerClassEntry(): JarEntry =
        checkNotNull(
            entries()
                .asSequence()
                .find { it.name == ListenerClassName }
        )

    private fun JavaClass.findListenerFunc(): Method =
        checkNotNull(
            methods.find(ListenerFuncPredicate)
        )

    private fun JarFile.listenerClass(): JavaClass =
        getInputStream(listenerClassEntry()).use {
            val parser = ClassParser(it, ListenerClassName)
            parser.parse()
        }

    private fun <T> File.useAsJarFile(block: JarFile.() -> T): T =
        JarFile(this).use {
            block(it)
        }
}

fun main() {
    PlayCore34Modifier(
        PlayCore34Modifier.Configuration(
            input = File("/path/to/input/play-core/library.aar"),
            output = File("/path/to/output/play-core/library.aar"),
        )
    ).modify()
}
