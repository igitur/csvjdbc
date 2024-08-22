/*
 *  CsvJdbc - a JDBC driver for CSV files
 *  Copyright (C) 2008  Mario Frasca
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package org.relique.io;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.relique.jdbc.csv.CsvResources;


/**
 * Class that collapses a set of files into one input stream. All files matching
 * a given pattern are collected, parts of the file name contains part of the
 * data, and the values in the file name are appended (or prepended) to each
 * data line.
 * 
 * @author Mario Frasca
 * 
 */
public class FileSetInputStream extends InputStream
{
	private String dirName;
	private List<String> fileNames;
	private EncryptedFileInputStream currentFile;
	private boolean readingHeader;
	private String tail;
	private int pos;
	private Pattern fileNameRE;
	private String separator;
	private Character quotechar;
	private String dataTail;
	private boolean prepend;
	private int lookahead = '\n';
	private int lookahead2 = -1;
	private boolean inQuotedValue = false;
	private boolean doingTail;
	private int currentLineLength;
	private CryptoFilter filter;
	private int skipLeadingDataLines;
	private String charset;
	private boolean isClosed = false;

	/**
	 * 
	 * @param dirName
	 *            the containing directory
	 * @param fileNamePattern
	 *            the regular expression describing the file name and the extra
	 *            fields.
	 * @param fieldsInName
	 *            the names of the fields contained in the file name.
	 * @param separator
	 *            the separator to use when faking output (typically the ",").
	 * @param quotechar the character used to quote column values containing the separator, or null for no quoting.
	 * @param prepend
	 *            whether the extra fields should precede the ones from the file
	 *            content.
	 * @param headerless if true, then there is no header on first line of file.
	 * @param filter filter for decoding bytes read from file.
	 * @param skipLeadingDataLines number of lines to skip at start of file before header line.
	 * @throws IOException if a file cannot be opened or read.
	 */
	public FileSetInputStream(String dirName, String fileNamePattern,
			String[] fieldsInName, String separator, Character quotechar, boolean prepend,
			boolean headerless, CryptoFilter filter, int skipLeadingDataLines,
			String charset)
			throws IOException
	{
		this.dirName = dirName;
		this.filter = filter;
		this.skipLeadingDataLines = skipLeadingDataLines;
		this.charset = charset;
		if (!headerless)
			this.skipLeadingDataLines++;
		
		// Initialising tail for header...
		this.prepend = prepend;
		this.separator = separator;
		this.quotechar = quotechar;
		tail = "";
		if (prepend)
		{
			tail += '\n';
		}
		else
		{
			if (fieldsInName != null)
				tail += separator;
		}
		if (fieldsInName != null)
		{
			for (int i = 0; i < fieldsInName.length; i++)
			{
				tail += fieldsInName[i];
				if (i + 1 < fieldsInName.length)
					tail += separator;
			}
		}
		if (prepend)
		{
			if (fieldsInName != null)
				tail += separator;
		}
		else
		{
			tail += '\n';
		}

		fileNames = new ArrayList<>();
		File root = new File(dirName);
		File[] candidates = root.listFiles();

		if (candidates == null)
		{
			throw new IOException(CsvResources.getString("dirNotFound") + ": " + dirName);
		}

		fileNameRE = Pattern.compile(fileNamePattern);

		for (int i = 0; i < candidates.length; i++)
		{
			if (!candidates[i].isDirectory())
			{
				String candidateName = candidates[i].getName();
				Matcher m = fileNameRE.matcher(candidateName);
				if (m.matches())
				{
					fileNames.add(candidateName);
				}
			}
		}
		Collections.sort(fileNames);
		if (fileNames.isEmpty())
		{
			return;
		}

		fileNameRE = Pattern.compile(".*" + fileNamePattern);
		readingHeader = true;
		String currentFileName = fileNames.remove(0);
		dataTail = getTailFromName(dirName + currentFileName);
		if (headerless)
			tail = dataTail;
		currentFile = new EncryptedFileInputStream(dirName + currentFileName, filter);
		lookahead = currentFile.read();
		if (lookahead == 0xFF && charset != null && charset.equals("UTF-16LE"))
		{
			// Skip any 0xFFFE Byte Order Mark at start of UTF-16LE file.
			currentFile.read();
			lookahead = currentFile.read();
		}
		doingTail = prepend;
		if (doingTail)
			pos = 1;
	}

	@Override
	public void close() throws IOException
	{
		isClosed = true;
		if (currentFile != null)
		{
			currentFile.close();
			currentFile = null;
		}
	}

	/**
	 * Reads the next byte of data from the input stream. The value byte is
	 * returned as an int in the range 0 to 255. if the end of the current
	 * source file is reached, the next single file is opened. if all input has
	 * been used, -1 is returned.
	 * 
	 * to output the tail, we just glue it before each '\n'
	 * 
	 * to output the lead, we have to look ahead and append it to all '\n' that
	 * are not followed by '\n' or -1
	 * 
	 * @return the next byte of data, or -1 if the end of the stream is reached.
	 * 
	 * @throws IOException
	 *             if an I/O error occurs.
	 */
	@Override
	public int read() throws IOException
	{
		if (isClosed)
			throw new IOException(CsvResources.getString("streamClosed"));

		// run out of input on all subfiles
		if (currentFile == null)
			return -1;

		int ch;
		if (doingTail)
		{
			ch = readFromTail();
			if (ch != -1)
			{
				return ch;
			}
			doingTail = false;
			currentLineLength = 0;
			inQuotedValue = false;
		}

		// shift the lookahead into the current char and get the new lookahead.
		ch = lookahead;
		do
		{
			if (lookahead2 != -1)
			{
				lookahead = lookahead2;
				lookahead2 = -1;
			}
			else
			{
				lookahead = currentFile.read();
			}
			// we ignore \r, which breaks things on files created with MacOS9
			if (lookahead == '\r')
			{
				if (charset != null && charset.equals("UTF-16LE"))
				{
					// Skip '\r' and '\0' for UTF-16LE charset.
					int skip = currentFile.read();
					if (skip != 0)
					{
						// Next char is some other valid UTF-16LE character,
						// not a carriage return, so we need to keep
						// it for later.
						lookahead2 = skip;
						break;
					}
				}
			}
		}
		while (lookahead == '\r');

		if (quotechar != null && ch == quotechar.charValue())
		{
			// Keep track of whether we are in a quoted column value, so we can
			// avoid adding the file tail to a column value spread across multiple
			// lines.
			inQuotedValue = !inQuotedValue;
		}

		// if we met a line border we have to output the lead/tail
		if (prepend)
		{
			// prepending a non empty line...
			if (ch == '\n' && !(lookahead == '\n' || lookahead == -1) && !inQuotedValue)
			{
				doingTail = true;
				ch = readFromTail();
				return ch;
			}
			if (lookahead == -1 && ch != '\n' && ch != -1)
			{
				// If last line of file does not end with a newline,
				// then add a newline, so prepended fields for the
				// next file begin on a new line.
				lookahead = '\n';
			}
		}
		else
		{
			// appending to the end of just any line
			if (currentLineLength > 0 && (ch == '\n' || ch == -1) && !inQuotedValue)
			{
				doingTail = true;
				if (ch == '\n' && lookahead == 0 &&
					charset != null && charset.equals("UTF-16LE"))
				{
					// Skip '\n' and '\0' for UTF-16LE charset.
					lookahead = currentFile.read();
				}

				ch = readFromTail();
				return ch;
			}
		}
		if (ch < 0)
		{
			currentFile.close();
			// open next file and possibly skip header
			pos = 0;
			String currentFileName;
			if (fileNames.size() > 0)
			{
				currentFileName = fileNames.remove(0);
			}
			else
			{
				currentFile = null;
				return -1;
			}
			tail = getTailFromName(dirName + currentFileName);
			currentFile = new EncryptedFileInputStream(dirName + currentFileName, filter);
			// if files do contain a header, skip it
			for(int i = 0; i < this.skipLeadingDataLines; i++)
			{
				int ch2;
				do
				{
					ch2 = currentFile.read();

					if (ch2 == '\n' && charset != null && charset.equals("UTF-16LE"))
					{
						// Skip '\n' and '\0' for UTF-16LE charset.
						int skip = currentFile.read();
						if (skip != 0)
						{
							// Next char is not a newline, so keep reading.
							ch2 = 0;
						}
					}
				}
				while (ch2 != '\n' && ch2 != -1);
			}
			doingTail = prepend;
			if (doingTail)
				pos = 1;
			lookahead = currentFile.read();
			if (lookahead == 0xFF && skipLeadingDataLines == 0 &&
				charset != null && charset.equals("UTF-16LE"))
			{
				// Skip any 0xFFFE Byte Order Mark at start of UTF-16LE file.
				currentFile.read();
				lookahead = currentFile.read();
			}
			ch = read();
			return ch;
		}
		currentLineLength++;
		return ch;
	}

	private String getTailFromName(String currentName)
	{
		Matcher m = fileNameRE.matcher(currentName);
		m.matches();
		String tail = "";
		int groupCount = m.groupCount();
		if (prepend)
		{
			tail += '\n';
		}
		else
		{
			if (groupCount > 0)
				tail += separator;
		}
		for (int i = 1; i <= groupCount; i++)
		{
			tail += m.group(i);
			if (i < groupCount)
				tail += separator;
		}
		if (prepend)
		{
			if (groupCount > 0)
				tail += separator;
		}
		else
		{
			tail += '\n';
		}
		return tail;
	}

	private int readFromTail() throws IOException
	{
		if (charset != null && charset.equals("UTF-16LE"))
		{
			byte []b = tail.getBytes(charset);
			if (pos < b.length)
				return b[pos++];
		}
		else
		{
			if (pos < tail.length())
				return tail.charAt(pos++);
		}
		pos = 0;
		if (readingHeader)
		{
			readingHeader = false;
			tail = dataTail;
		}
		return -1;
	}

	@Override
	public synchronized void reset() throws IOException
	{
		super.reset();
	}
}
