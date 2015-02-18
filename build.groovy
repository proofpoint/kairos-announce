import org.freecompany.redline.Builder
import org.freecompany.redline.header.Architecture
import org.freecompany.redline.header.Os
import org.freecompany.redline.header.RpmType
import org.freecompany.redline.payload.Directive
import tablesaw.AbstractFileSet
import tablesaw.RegExFileSet
import tablesaw.Tablesaw
import tablesaw.TablesawException
import tablesaw.addons.GZipRule
import tablesaw.addons.TarRule
import tablesaw.addons.ivy.IvyAddon
import tablesaw.addons.java.Classpath
import tablesaw.addons.java.JavaCRule
import tablesaw.addons.java.JavaProgram
import tablesaw.addons.junit.JUnitRule
import tablesaw.rules.DirectoryRule
import tablesaw.rules.RequiredPropertyRule
import tablesaw.rules.Rule
import tablesaw.rules.SimpleRule

import javax.swing.*

println("===============================================");

saw.setProperty(Tablesaw.PROP_MULTI_THREAD_OUTPUT, Tablesaw.PROP_VALUE_ON)

programName = "kairosdb-announce"
//Do not use '-' in version string, it breaks rpm uninstall.
version = saw.getProperty('VERSION_NUMBER') || "1.0";
release = saw.getProperty('BUILD_NUMBER') || '3'; //package release number
summary = "KairosDB Announce"
description = """\
KairosDB-Announce is a KairosDB plugin to announce to platform.
"""

saw = Tablesaw.getCurrentTablesaw()
saw.includeDefinitionFile("definitions.xml")

ivyConfig = ["default"]


rpmDir = "build/rpm"
rpmNoDepDir = "build/rpm-nodep"
new DirectoryRule("build")
rpmDirRule = new DirectoryRule(rpmDir)
rpmNoDepDirRule = new DirectoryRule(rpmNoDepDir)

ivy = new IvyAddon().setup()

jp = new JavaProgram().setProgramName(programName)
		.setup()
jp.getCompileRule().getDefinition().set("target", "1.6")
jp.getCompileRule().getDefinition().set("source", "1.6")

jp.getJarRule().addFiles("src/main/resources", "kairosdb-announce.properties")

jc = jp.getCompileRule()
jc.addDepend(ivy.getResolveRule("default"))

//Set information in the manifest file
manifest = jp.getJarRule().getManifest().getMainAttributes()
manifest.putValue("Manifest-Version", "1.0")
manifest.putValue("Tablesaw-Version", saw.getVersion())
manifest.putValue("Created-By", saw.getProperty("java.vm.version")+" ("+
			saw.getProperty("java.vm.vendor")+")")
manifest.putValue("Built-By", saw.getProperty("user.name"))
buildDateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z")
manifest.putValue("Build-Date", buildDateFormat.format(new Date()))

buildNumberFormat = new java.text.SimpleDateFormat("yyyyMMddHHmmss");
buildNumber = buildNumberFormat.format(new Date())
manifest.putValue("Implementation-Title", "KairosDB-Announce")
manifest.putValue("Implementation-Vendor", "Proofpoint Inc.")
manifest.putValue("Implementation-Version", "${version}.${buildNumber}")

//Add git revision information
gitRevisionFile= ".gitrevision"
new File(gitRevisionFile).text = ""
saw.exec(null, "git rev-parse HEAD", false, null, gitRevisionFile);
revision = new File(gitRevisionFile).text.trim()
new File(gitRevisionFile).delete()
manifest.putValue("Git-Revision", revision);

saw.setDefaultTarget("jar")


testClasspath = new Classpath(jp.getLibraryJars())
testClasspath.addPath(jp.getJarRule().getTarget())


testSources = new RegExFileSet("src/test/java", ".*Test\\.java").recurse()
		.addExcludeFiles("CassandraDatastoreTest.java", "HBaseDatastoreTest.java")
		.getFilePaths()
testCompileRule = jp.getTestCompileRule()

junitClasspath = new Classpath(testCompileRule.getClasspath())
junitClasspath.addPaths(testClasspath)
junitClasspath.addPath("src/main/java")
junitClasspath.addPath("src/test/resources")
junitClasspath.addPath("src/main/resources")

junit = new JUnitRule().addSources(testSources)
		.setClasspath(junitClasspath)
		.addDepends(testCompileRule)

if (saw.getProperty("jacoco", "false").equals("true"))
	junit.addJvmArgument("-javaagent:lib_test/jacocoagent.jar=destfile=build/jacoco.exec")

testSourcesAll = new RegExFileSet("src/test/java", ".*Test\\.java").recurse().getFilePaths()
junitAll = new JUnitRule("junit-test-all").setDescription("Run unit tests including Cassandra and HBase tests")
		.addSources(testSourcesAll)
		.setClasspath(junitClasspath)
		.addDepends(testCompileRule)

if (saw.getProperty("jacoco", "false").equals("true"))
	junitAll.addJvmArgument("-javaagent:lib_test/jacocoagent.jar=destfile=build/jacoco.exec")

//------------------------------------------------------------------------------
//Build zip deployable application
rpmFile = "$programName-$version-${release}.rpm"
srcRpmFile = "$programName-$version-${release}.src.rpm"
libFileSets = [
		new RegExFileSet("build/jar", ".*\\.jar"),
		new RegExFileSet("lib", ".*\\.jar"),
		new RegExFileSet("lib/ivy/default", ".*\\.jar")
	]

scriptsFileSet = new RegExFileSet("src/scripts", ".*").addExcludeFile("kairosdb-env.sh")
webrootFileSet = new RegExFileSet("webroot", ".*").recurse()

zipLibDir = "lib"
zipConfDir = "conf"
tarRule = new TarRule("build/${programName}-${version}.tar")
		.addDepend(jp.getJarRule())
		.addFileTo(zipConfDir, "src/main/resources", "kairosdb-announce.properties")

for (AbstractFileSet fs in libFileSets)
	tarRule.addFileSetTo(zipLibDir, fs)


gzipRule = new GZipRule("package").setSource(tarRule.getTarget())
		.setDescription("Create deployable tar file")
		.setTarget("build/${programName}-${version}.tar.gz")
		.addDepend(tarRule)

//------------------------------------------------------------------------------
//Build rpm file
rpmBaseInstallDir = "/opt/kairosdb"
rpmRule = new SimpleRule("package-rpm").setDescription("Build RPM Package")
		.addDepend(jp.getJarRule())
		.addDepend(rpmDirRule)
		.addTarget("$rpmDir/$rpmFile")
		.setMakeAction("doRPM")
		.setProperty("dependency", "on")

new SimpleRule("package-rpm-nodep").setDescription("Build RPM Package with no dependencies")
		.addDepend(jp.getJarRule())
		.addDepend(rpmNoDepDirRule)
		.addTarget("${rpmNoDepDir}/$rpmFile")
		.setMakeAction("doRPM")

def doRPM(Rule rule)
{
	//Build rpm using redline rpm library
	host = InetAddress.getLocalHost().getHostName()
	rpmBuilder = new Builder()
	rpmBuilder.with
			{
				description = description
				group = "System Environment/Daemons"
				license = "license"
				setPackage(programName, version, release)
				setPlatform(Architecture.NOARCH, Os.LINUX)
				summary = summary
				type = RpmType.BINARY
				url = "https://github.com/proofpoint/kairos-announce"
				vendor = "Proofpoint Inc."
				provides = programName
				//prefixes = rpmBaseInstallDir
				buildHost = host
				sourceRpm = srcRpmFile
			}

	if ("on".equals(rule.getProperty("dependency")))
		rpmBuilder.addDependencyMore("kairosdb", "0.9.3")

	//rpmBuilder.setPostInstallScript(new File("src/scripts/install/post_install.sh"))
	//rpmBuilder.setPreUninstallScript(new File("src/scripts/install/pre_uninstall.sh"))

	for (AbstractFileSet fs in libFileSets)
		addFileSetToRPM(rpmBuilder, "$rpmBaseInstallDir/lib", fs)

	rpmBuilder.addFile("$rpmBaseInstallDir/conf/kairosdb-announce.properties",
			new File("src/main/resources/kairosdb-announce.properties"), 0644,
			new Directive(Directive.RPMFILE_CONFIG | Directive.RPMFILE_NOREPLACE))

	println("Building RPM "+rule.getTarget())
	outputFile = new FileOutputStream(rule.getTarget())
	rpmBuilder.build(outputFile.channel)
	outputFile.close()
}

def addFileSetToRPM(Builder builder, String destination, AbstractFileSet files)
{
	for (String filePath : files.getFullFilePaths())
	{
		File f = new File(filePath)
		if (f.getName().endsWith(".sh"))
			builder.addFile(destination + "/" +f.getName(), f, 0755)
		else
			builder.addFile(destination + "/" + f.getName(), f)
	}
}

