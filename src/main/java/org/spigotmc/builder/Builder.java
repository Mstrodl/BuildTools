package org.spigotmc.builder;

import com.google.common.base.Charsets;
import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import difflib.DiffUtils;
import difflib.Patch;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import lombok.RequiredArgsConstructor;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;

public class Builder
{

    public static final boolean IS_WINDOWS = System.getProperty( "os.name" ).startsWith( "Windows" );
    public static final boolean IS_MAC = System.getProperty( "os.name" ).startsWith( "Mac" );
    public static final File CWD = new File( "." );
    public static final String MC_VERSION = "1.8";

    public static void main(String[] args) throws Exception
    {
        if ( IS_MAC )
        {
            System.out.println( "Sorry, but Macintosh is not currently a supported platform for compilation at this time." );
            System.out.println( "Please run this script on a Windows or Linux PC and then copy the jars to this computer." );
            return;
        }

        try
        {
            runProcess( "bash -c exit", CWD );
        } catch ( Exception ex )
        {
            System.out.println( "You must run this jar through bash (msysgit)" );
            return;
        }

        try
        {
            runProcess( "git config --global user.name", CWD );
        } catch ( Exception ex )
        {
            System.out.println( "Git name not set, setting it to default value." );
            runProcess( "git config --global user.name BuildTools", CWD );
        }
        try
        {
            runProcess( "git config --global user.email", CWD );
        } catch ( Exception ex )
        {
            System.out.println( "Git email not set, setting it to default value." );
            runProcess( "git config --global user.email unconfigured@null.spigotmc.org", CWD );
        }

        File workDir = new File( "work" );
        workDir.mkdir();

        File bukkit = new File( "Bukkit" );
        if ( !bukkit.exists() )
        {
            clone( "https://hub.spigotmc.org/stash/scm/spigot/bukkit.git", bukkit );
        }

        File craftBukkit = new File( "CraftBukkit" );
        if ( !craftBukkit.exists() )
        {
            clone( "https://hub.spigotmc.org/stash/scm/spigot/craftbukkit.git", craftBukkit );
        }

        File spigot = new File( "Spigot" );
        if ( !spigot.exists() )
        {
            clone( "https://hub.spigotmc.org/stash/scm/spigot/spigot.git", spigot );
        }

        File buildData = new File( "BuildData" );
        if ( !buildData.exists() )
        {
            clone( "https://hub.spigotmc.org/stash/scm/spigot/builddata.git", buildData );
        }

        File maven = new File( "apache-maven-3.2.3" );
        if ( !maven.exists() )
        {
            System.out.println( "Maven does not exist, downloading. Please wait." );

            File mvnTemp = new File( "mvn.zip" );
            mvnTemp.deleteOnExit();

            download( "http://static.spigotmc.org/maven/apache-maven-3.2.3-bin.zip", mvnTemp );
            unzip( mvnTemp, new File( "." ) );
        }

        String mvnCmd = maven.getAbsolutePath() + "/bin/mvn";
        if ( IS_WINDOWS )
        {
            mvnCmd += ".bat";
        } else
        {
            mvnCmd = "/bin/sh " + mvnCmd;
        }

        Git bukkitGit = Git.open( bukkit );
        Git craftBukkitGit = Git.open( craftBukkit );
        Git spigotGit = Git.open( spigot );
        Git buildGit = Git.open( buildData );

        pull( bukkitGit );
        pull( craftBukkitGit );
        pull( spigotGit );
        pull( buildGit );

        File vanillaJar = new File( workDir, "minecraft_server." + MC_VERSION + ".jar" );
        if ( !vanillaJar.exists() )
        {
            download( String.format( "https://s3.amazonaws.com/Minecraft.Download/versions/%1$s/minecraft_server.%1$s.jar", MC_VERSION ), vanillaJar );
        }

        Iterable<RevCommit> mappings = buildGit.log()
                .addPath( "mappings/bukkit-1.8.at" )
                .addPath( "mappings/bukkit-1.8-cl.csrg" )
                .addPath( "mappings/bukkit-1.8-members.csrg" )
                .addPath( "mappings/package.srg" )
                .setMaxCount( 1 ).call();

        Hasher mappingsHash = Hashing.md5().newHasher();
        for ( RevCommit rev : mappings )
        {
            mappingsHash.putString( rev.getName(), Charsets.UTF_8 );
        }
        String mappingsVersion = mappingsHash.hash().toString().substring( 24 ); // Last 8 chars

        File finalMappedJar = new File( workDir, "mapped." + mappingsVersion + ".jar" );
        if ( !finalMappedJar.exists() )
        {
            System.out.println( "Final mapped jar: " + finalMappedJar + " does not exist, creating!" );

            File clMappedJar = new File( finalMappedJar + "-cl" );
            File mMappedJar = new File( finalMappedJar + "-m" );

            runProcess( "java -jar BuildData/bin/SpecialSource.jar -i " + vanillaJar + " -m BuildData/mappings/bukkit-1.8-cl.csrg -o " + clMappedJar, CWD );
            runProcess( "java -jar BuildData/bin/SpecialSource-2.jar map -i " + clMappedJar + " -m " + "BuildData/mappings/bukkit-1.8-members.csrg -o " + mMappedJar, CWD );
            runProcess( "java -jar BuildData/bin/SpecialSource.jar -i " + mMappedJar + " --access-transformer BuildData/mappings/bukkit-1.8.at "
                    + "-m BuildData/mappings/package.srg -o " + finalMappedJar, CWD );
        }

        runProcess( mvnCmd + " install:install-file -Dfile=" + finalMappedJar + " -Dpackaging=jar -DgroupId=org.spigotmc -DartifactId=minecraft-server -Dversion=1.8-SNAPSHOT", CWD );

        File decompileDir = new File( workDir, "decompile-" + mappingsVersion );
        if ( !decompileDir.exists() )
        {
            decompileDir.mkdir();

            File clazzDir = new File( decompileDir, "classes" );
            unzip( finalMappedJar, clazzDir, new Predicate<String>()
            {

                @Override
                public boolean apply(String input)
                {
                    return input.startsWith( "net/minecraft/server" );
                }
            } );

            runProcess( "java -jar BuildData/bin/fernflower.jar -dgs=1 -hdc=0 -rbr=0 -asc=1 " + clazzDir + " " + decompileDir, CWD );

            String jacobePath = "BuildData/bin/jacobe";
            if ( IS_WINDOWS )
            {
                jacobePath += ".exe";
            }
            runProcess( jacobePath + " -cfg=BuildData/bin/jacobe.cfg -nobackup -overwrite -outext=java " + decompileDir + "/net/minecraft/server", CWD );
        }

        System.out.println( "Applying CraftBukkit Patches" );
        File nmsDir = new File( craftBukkit, "src/main/java/net" );
        if ( nmsDir.exists() )
        {
            System.out.println( "Backing up NMS dir" );
            FileUtils.moveDirectory( nmsDir, new File( workDir, "nms.old." + System.currentTimeMillis() ) );
        }
        File patchDir = new File( craftBukkit, "nms-patches" );
        for ( File file : patchDir.listFiles() )
        {
            String targetFile = "net/minecraft/server/" + file.getName().replaceAll( ".patch", ".java" );

            File clean = new File( decompileDir, targetFile );
            File t = new File( nmsDir.getParentFile(), targetFile );
            t.getParentFile().mkdirs();

            System.out.println( "Patching with " + file.getName() );

            Patch parsedPatch = DiffUtils.parseUnifiedDiff( Files.readLines( file, Charsets.UTF_8 ) );
            List<?> modifiedLines = DiffUtils.patch( Files.readLines( clean, Charsets.UTF_8 ), parsedPatch );

            BufferedWriter bw = new BufferedWriter( new FileWriter( t ) );
            for ( String line : (List<String>) modifiedLines )
            {
                bw.write( line );
                bw.newLine();
            }
            bw.close();
        }
        File tmpNms = new File( craftBukkit, "tmp-nms" );
        FileUtils.copyDirectory( nmsDir, tmpNms );

        craftBukkitGit.branchDelete().setBranchNames( "patched" ).setForce( true ).call();
        craftBukkitGit.checkout().setCreateBranch( true ).setForce( true ).setName( "patched" ).call();
        craftBukkitGit.add().addFilepattern( "src/main/java/net/" ).call();
        craftBukkitGit.commit().setMessage( "CraftBukkit $ " + new Date() ).call();
        craftBukkitGit.checkout().setName( "master" ).call();

        FileUtils.moveDirectory( tmpNms, nmsDir );

        File spigotApi = new File( spigot, "Bukkit" );
        if ( !spigotApi.exists() )
        {
            clone( "file://" + bukkit.getAbsolutePath(), spigotApi );
        }
        File spigotServer = new File( spigot, "CraftBukkit" );
        if ( !spigotServer.exists() )
        {
            clone( "file://" + craftBukkit.getAbsolutePath(), spigotServer );
        }

        // Git spigotApiGit = Git.open( spigotApi );
        // Git spigotServerGit = Git.open( spigotServer );
        System.out.println( "Compiling Bukkit" );
        runProcess( mvnCmd + " clean install", bukkit );

        System.out.println( "Compiling CraftBukkit" );
        runProcess( mvnCmd + " clean install", craftBukkit );

        try
        {
            runProcess( "bash applyPatches.sh", spigot );
            System.out.println( "*** Spigot patches applied!" );
            System.out.println( "Compiling Spigot & Spigot-API" );
            runProcess( mvnCmd + " clean install", spigot );
        } catch ( Exception ex )
        {
            System.err.println( "Error compiling Spigot, are you running this jar via msysgit?" );
            ex.printStackTrace();
        }
    }

    public static void pull(Git repo) throws Exception
    {
        System.out.println( "Pulling updates for " + repo.getRepository().getDirectory() );

        repo.reset().setRef( "origin/master" ).setMode( ResetCommand.ResetType.HARD ).call();
        boolean result = repo.pull().call().isSuccessful();

        if ( !result )
        {
            throw new RuntimeException( "Could not pull updates!" );
        }

        System.out.println( "Successfully pulled updates!" );
    }

    public static int runProcess(String command, File workDir) throws Exception
    {
        final Process ps = new ProcessBuilder( command.split( " " ) ).directory( workDir ).start();

        new Thread( new StreamRedirector( ps.getInputStream(), System.out ) ).start();
        new Thread( new StreamRedirector( ps.getErrorStream(), System.err ) ).start();

        int status = ps.waitFor();

        if ( status != 0 )
        {
            throw new RuntimeException( "Error running command, return status !=0: " + command );
        }

        return status;
    }

    @RequiredArgsConstructor
    private static class StreamRedirector implements Runnable
    {

        private final InputStream in;
        private final PrintStream out;

        @Override
        public void run()
        {
            BufferedReader br = new BufferedReader( new InputStreamReader( in ) );
            try
            {
                String line;
                while ( ( line = br.readLine() ) != null )
                {
                    out.println( line );
                }
            } catch ( IOException ex )
            {
                throw Throwables.propagate( ex );
            }
        }
    }

    public static void unzip(File zipFile, File targetFolder) throws IOException
    {
        unzip( zipFile, targetFolder, null );
    }

    public static void unzip(File zipFile, File targetFolder, Predicate<String> filter) throws IOException
    {
        targetFolder.mkdir();
        ZipFile zip = new ZipFile( zipFile );

        for ( Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); )
        {
            ZipEntry entry = entries.nextElement();

            if ( filter != null )
            {
                if ( !filter.apply( entry.getName() ) )
                {
                    continue;
                }
            }

            File outFile = new File( targetFolder, entry.getName() );

            if ( entry.isDirectory() )
            {
                outFile.mkdirs();
                continue;
            }
            if ( outFile.getParentFile() != null )
            {
                outFile.getParentFile().mkdirs();
            }

            InputStream is = zip.getInputStream( entry );
            OutputStream os = new FileOutputStream( outFile );
            try
            {
                ByteStreams.copy( is, os );
            } finally
            {
                is.close();
                os.close();
            }

            System.out.println( "Extracted: " + outFile );
        }
    }

    public static void clone(String url, File target) throws GitAPIException
    {
        System.out.println( "Starting clone of " + url + " to " + target );

        Git result = Git.cloneRepository().setURI( url ).setDirectory( target ).call();

        try
        {
            System.out.println( "Cloned git repository " + url + " to " + url + ". Current HEAD: " + commitHash( result ) );

        } finally
        {
            result.close();
        }
    }

    public static String commitHash(Git repo) throws GitAPIException
    {
        return Iterables.getOnlyElement( repo.log().setMaxCount( 1 ).call() ).getName();
    }

    public static File download(String url, File target) throws IOException
    {
        System.out.println( "Starting download of " + url );

        byte[] bytes = Resources.toByteArray( new URL( url ) );

        System.out.println( "Downloaded file: " + target + " with md5: " + Hashing.md5().hashBytes( bytes ).toString() );

        Files.write( bytes, target );

        return target;
    }
}