package com.cromulentlabs.appengine.cloudendpoints;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.protocol.http.util.accesslog.Filter;
import org.apache.jmeter.protocol.http.util.accesslog.LogParser;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;

public class GoogleCloudEndpointsLogParser implements LogParser
{
	protected static final Logger log = LoggingManager.getLoggerForClass();
	protected static final Pattern INPUT_FILE_PATTERN = Pattern
	    .compile("^fetch_(\\d+)\\.txt$");
	protected static final Pattern REQUEST_LINE_PATTERN = Pattern
	    .compile("^Request: ([A-Z]+) (.*)$");

	protected String sourceDirectory = null;
	protected File[] sourceFiles = null;
	protected BufferedReader[] sourceReaders = null;
	protected int currentFilePointer = 0;

	public void close()
	{
		if (this.sourceReaders != null)
		{
			for (BufferedReader reader : this.sourceReaders)
			{
				if (reader != null)
				{
					try
					{
						reader.close();
					}
					catch (Exception e)
					{
						log.warn("Unable to close reader", e);
					}
				}
			}
		}
	}

	public int parseAndConfigure(int count, TestElement el)
	{
		if (log.isDebugEnabled())
		{
			log.debug("sourceDirectory = " + this.sourceDirectory);
		}
		if (this.sourceDirectory == null)
		{
			return -1;
		}
		if (this.sourceFiles == null)
		{
			File rootDir = new File(sourceDirectory);
			if (!rootDir.isDirectory())
			{
				throw new RuntimeException("Source File must be directory");
			}
			this.sourceFiles = rootDir.listFiles(new FilenameFilter()
			{
				public boolean accept(File dir, String name)
				{
					if (INPUT_FILE_PATTERN.matcher(name).matches())
					{
						return true;
					}
					return false;
				}
			});
			Arrays.sort(this.sourceFiles, new Comparator<File>()
			{
				public int compare(File f0, File f1)
				{
					return new Integer(parseFileInputNumber(f0.getName()))
					    .compareTo(parseFileInputNumber(f1.getName()));
				}
			});
			this.sourceReaders = new BufferedReader[this.sourceFiles.length];
			for (int i = 0; i < this.sourceFiles.length; i++)
			{
				try
				{
					log.info("Opening file: " + this.sourceFiles[i]);
					this.sourceReaders[i] = new BufferedReader(new FileReader(
					    this.sourceFiles[i]));
				}
				catch (FileNotFoundException e)
				{
					throw new RuntimeException(
					    "Hit exception attempting to open input file: "
					        + this.sourceFiles[i] + ": " + e);
				}
			}
		}
		if (this.currentFilePointer < this.sourceReaders.length)
		{
			int i = 0;
			for (; i < count; i++)
			{
				BufferedReader reader = this.sourceReaders[this.currentFilePointer + i];
				try
				{
					log.info("Parsing file: " + this.sourceFiles[i]);
					parseFile(reader, el);
				}
				catch (IOException ioe)
				{
					log.error("Error reading log file", ioe);
				}
			}
			this.currentFilePointer += i;
			return i;
		}
		return -1;
	}

	public void setFilter(Filter filter)
	{
		// TODO Auto-generated method stub

	}

	public void setSourceFile(String sourceFile)
	{
		this.sourceDirectory = sourceFile;
	}

	private static int parseFileInputNumber(String filename)
	{
		int i = 0;
		Matcher m = INPUT_FILE_PATTERN.matcher(filename);
		if (m.matches())
		{
			String num = m.group(1);
			try
			{
				i = Integer.parseInt(num);
			}
			catch (NumberFormatException e)
			{
				// Ignore
			}
		}
		return i;
	}

	private void parseFile(BufferedReader reader, TestElement el)
	    throws IOException
	{
		boolean requestParsed = false;
		boolean inHeaders = false;
		boolean inRequest = false;
		List<Header> headers = new ArrayList<Header>();
		StringBuffer body = new StringBuffer();
		String line = null;
		while ((line = reader.readLine()) != null)
		{
			if (inHeaders)
			{
				if (line.trim().length() == 0)
				{
					inHeaders = false;
					HeaderManager hm = new HeaderManager();
					for (Header header : headers)
					{
						hm.add(header);
					}
					el.setProperty(new TestElementProperty(
					    HTTPSamplerBase.HEADER_MANAGER, hm));
				}
				else
				{
					if (log.isDebugEnabled())
					{
						log.debug("Adding header: " + line);
					}
					if (line.contains(":"))
					{
						String[] headerParts = line.trim().split(":", 2);
						headers.add(new Header(headerParts[0], headerParts[1]));
					}
				}
			}
			else if (inRequest)
			{
				if (line.trim().length() == 0)
				{
					inRequest = false;
					if (log.isDebugEnabled())
					{
						log.debug("Setting post body: " + body);
					}
					el.setProperty(HTTPSamplerBase.POST_BODY_RAW, true);
					Arguments args = new Arguments();
					HTTPArgument argument = new HTTPArgument("",
					    body.toString(), "", false, "UTF-8");
					argument.setAlwaysEncoded(false);
					args.addArgument(argument);
					el.setProperty(new TestElementProperty(
					    HTTPSamplerBase.ARGUMENTS, args));
					break;
				}
				else
				{
					body.append(line);
				}
			}
			else if (!requestParsed)
			{
				if (log.isDebugEnabled())
				{
					log.debug("Checking line: " + line);
				}
				Matcher requestMatcher = REQUEST_LINE_PATTERN.matcher(line);
				if (requestMatcher.matches())
				{
					String method = requestMatcher.group(1);
					String url = requestMatcher.group(2);
					if (log.isDebugEnabled())
					{
						log.debug("Setting method = " + method + ", url = "
						    + url);
					}
					el.setProperty(HTTPSamplerBase.METHOD, method);
					el.setProperty(HTTPSamplerBase.PATH, url);
					requestParsed = true;
				}
			}
			else if (line.contains("Request headers:"))
			{
				log.debug("inHeaders");
				inHeaders = true;
			}
			else if (line.contains("Request body:"))
			{
				log.debug("inRequest");
				inRequest = true;
			}
		}
	}
}
