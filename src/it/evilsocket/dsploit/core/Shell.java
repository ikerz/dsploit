/*
 * This file is part of the dSploit.
 *
 * Copyleft of Simone Margaritelli aka evilsocket <evilsocket@gmail.com>
 *
 * dSploit is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * dSploit is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with dSploit.  If not, see <http://www.gnu.org/licenses/>.
 */
package it.evilsocket.dsploit.core;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;

import android.content.Context;

public class Shell 
{
	private static final String TAG = "SHELL";
	
	private static Process spawnShell( String command, boolean bUpdateLibraryPath, boolean bRedirectErrorStream ) throws IOException {
		ProcessBuilder 		builder 	= new ProcessBuilder().command( command );
		Map<String, String> environment = builder.environment();
		
		builder.redirectErrorStream( bRedirectErrorStream );
		
		if( bUpdateLibraryPath )
		{
			boolean found  = false;
			String libPath = System.getLibraryPath();

			for( Map.Entry<String, String> entry : environment.entrySet() )
			{
				if( entry.getKey().equals("LD_LIBRARY_PATH") )
				{
					environment.put( "LD_LIBRARY_PATH", entry.getValue() + ":" + libPath );
					found = true;
					break;
				}								
			}
			
			if( found == false )			
				environment.put( "LD_LIBRARY_PATH", libPath );			
		}
		
		return builder.start();
	}
	
	private static Process spawnShell( String command ) throws IOException {
		return spawnShell( command, false, true ); 
	}
	
	public static boolean isRootGranted( ) {
		Process			 process = null;
		DataOutputStream writer  = null;
		BufferedReader   reader  = null;
		String			 line    = null;
		boolean 		 granted = false;

		try 
		{
			process = spawnShell( "su" );
			writer  = new DataOutputStream( process.getOutputStream() );
			reader  = new BufferedReader( new InputStreamReader(process.getInputStream()) );

			writer.writeBytes( "id\n" );
			writer.flush();
			writer.writeBytes( "exit\n" );
			writer.flush();
			
			while( ( line = reader.readLine() ) != null && !granted )
			{
				if( line.toLowerCase().contains("uid=0") )
					granted = true;
			}

			process.waitFor();

		} 
		catch ( Exception e ) 
		{
			System.errorLogging( TAG, e );
		} 
		finally 
		{
			try 
			{
				if( writer != null ) 
					writer.close();
				
				if( reader != null )
					reader.close();
			} 
			catch (IOException e) {
				// ignore errors
			} 			
		}
		
		return granted;		
	}
	
	public interface OutputReceiver
	{
		public void onStart( String command );
		public void onNewLine( String line );
		public void onEnd( int exitCode );
	}
	
	public static boolean isBinaryAvailable( String binary )
	{
		try
		{
			Process 	     process  = spawnShell( "sh" );
			DataOutputStream writer   = new DataOutputStream( process.getOutputStream() );;
			BufferedReader   reader   = new BufferedReader( new InputStreamReader( process.getInputStream() ) );  
			String		     line	  = null;
			
			writer.writeBytes( "which " + binary + "\n" );
			writer.flush();
			writer.writeBytes( "exit\n" );
			writer.flush();
			
			while( ( line = reader.readLine() ) != null ) 
			{  
				if( line.isEmpty() == false && line.startsWith("/") )					
					return true;
			}  				
		}
		catch( Exception e )
		{
			System.errorLogging( TAG, e );
		}
				
		return false;
	}
	
	public static boolean isLibraryPathOverridable( Context context ) {
		boolean linkerError = false;
		
		try
		{
			String libPath  = System.getLibraryPath(),
				   fileName = context.getFilesDir().getAbsolutePath() + "/tools/nmap/nmap";
			File   file	    = new File( fileName );
			String dirName  = file.getParent(),
				   command  = "cd " + dirName + " && ./nmap --version";
			Process			 process = spawnShell( "su", true, false );
			DataOutputStream writer  = null;
			BufferedReader   reader  = null,
							 stderr  = null;
			String			 line    = null,
							 error   = null;
			
			writer = new DataOutputStream( process.getOutputStream() );
			reader = new BufferedReader( new InputStreamReader( process.getInputStream() ) );
			stderr = new BufferedReader( new InputStreamReader( process.getErrorStream() ) );
			
			writer.writeBytes( "export LD_LIBRARY_PATH=" + libPath + ":$LD_LIBRARY_PATH\n" );
			writer.flush();
			
			writer.writeBytes( command + "\n" );
			writer.flush();
			writer.writeBytes( "exit\n" );
			writer.flush();

			while( ( ( line = reader.readLine() ) != null || ( error = stderr.readLine() ) != null ) && !linkerError )
			{
				line = line == null ? error : line;
				
				if( line.contains("CANNOT LINK EXECUTABLE") )
					linkerError = true;
			}
			// flush stderr		
			while( ( error = stderr.readLine() ) != null && !linkerError )
			{
				if( line.contains("CANNOT LINK EXECUTABLE") )
					linkerError = true;
			}

			process.waitFor();			
		}
		catch( Exception e )
		{
			System.errorLogging( TAG, e );
			
			return !linkerError;
		}
		
		return !linkerError;
	}
	
	public static int exec( String command, OutputReceiver receiver, boolean overrideLibraryPath ) throws IOException, InterruptedException {
		Process			 process = spawnShell( "su", overrideLibraryPath, true );
		DataOutputStream writer  = null;
		BufferedReader   reader  = null;
		String			 line    = null,
						 libPath = System.getLibraryPath();
						
		if( receiver != null ) receiver.onStart( command );
				
		writer = new DataOutputStream( process.getOutputStream() );
		reader = new BufferedReader( new InputStreamReader( process.getInputStream() ) );
		
		// is this really needed ?
		if( overrideLibraryPath )
		{
			writer.writeBytes( "export LD_LIBRARY_PATH=" + libPath + ":$LD_LIBRARY_PATH\n" );
			writer.flush();
		}
		
		writer.writeBytes( "\n" );
		writer.flush();
		writer.writeBytes( command + "\n" );
		writer.flush();
		writer.writeBytes( "exit\n" );
		writer.flush();

		while( ( line = reader.readLine() ) != null )
		{			
			if( receiver != null ) 
				receiver.onNewLine( line );				
		}

		int exit = process.waitFor();
		
		if( receiver != null ) receiver.onEnd( exit );
		
		return exit;
	}
	
	public static int exec( String command, OutputReceiver reciever ) throws IOException, InterruptedException {
		return exec( command, reciever, true );
	}
	
	public static int exec( String command ) throws IOException, InterruptedException {
		return exec( command, null, true );
	}
	
	public static Thread async( final String command, final OutputReceiver receiver, final boolean overrideLibraryPath ) {
		
		return new Thread( new Runnable(){
			@Override
			public void run() {
				try
				{
					exec( command, receiver, overrideLibraryPath );
				}
				catch( Exception e )
				{
					System.errorLogging( TAG, e );
				}
			}} 
		);
	}
	
	public static Thread async( String command ) {
		return async( command, null, true );
	}
	
	public static Thread async( final String command, final OutputReceiver receiver ) {
		return async( command, receiver, true );
	}
}
