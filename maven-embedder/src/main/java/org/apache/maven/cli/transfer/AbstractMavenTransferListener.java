package org.apache.maven.cli.transfer;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.PrintStream;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

import org.apache.commons.lang3.Validate;
import org.eclipse.aether.transfer.AbstractTransferListener;
import org.eclipse.aether.transfer.TransferCancelledException;
import org.eclipse.aether.transfer.TransferEvent;
import org.eclipse.aether.transfer.TransferResource;

public abstract class AbstractMavenTransferListener
    extends AbstractTransferListener
{

    // CHECKSTYLE_OFF: LineLength
    /**
     * Formats file size with the associated <a href="https://en.wikipedia.org/wiki/Metric_prefix">SI</a> prefix
     * (GB, MB, kB) and using the patterns <code>#0.0</code> for numbers between 1 and 10
     * and <code>###0</code> for numbers between 10 and 1000+ by default.
     *
     * @see <a href="https://en.wikipedia.org/wiki/Metric_prefix">https://en.wikipedia.org/wiki/Metric_prefix</a>
     * @see <a href="https://en.wikipedia.org/wiki/Binary_prefix">https://en.wikipedia.org/wiki/Binary_prefix</a>
     * @see <a
     *      href="https://en.wikipedia.org/wiki/Octet_%28computing%29">https://en.wikipedia.org/wiki/Octet_(computing)</a>
     */
    // CHECKSTYLE_ON: LineLength
    // TODO Move me to Maven Shared Utils
    static class FileSizeFormat
    {
        static enum ScaleUnit
        {
            BYTE
            {
                @Override
                public long bytes()
                {
                    return 1L;
                }

                @Override
                public String symbol()
                {
                    return "B";
                }
            },
            KILOBYTE
            {
                @Override
                public long bytes()
                {
                    return 1000L;
                }

                @Override
                public String symbol()
                {
                    return "kB";
                }
            },
            MEGABYTE
            {
                @Override
                public long bytes()
                {
                    return KILOBYTE.bytes() * KILOBYTE.bytes();
                }

                @Override
                public String symbol()
                {
                    return "MB";
                }
            },
            GIGABYTE
            {
                @Override
                public long bytes()
                {
                    return MEGABYTE.bytes() * KILOBYTE.bytes();
                };

                @Override
                public String symbol()
                {
                    return "GB";
                }
            };

            public abstract long bytes();
            public abstract String symbol();

            public static ScaleUnit getScaleUnit( long size )
            {
                Validate.isTrue( size >= 0, "File size cannot be negative: %s", size );

                if ( size >= GIGABYTE.bytes() )
                {
                    return GIGABYTE;
                }
                else if ( size >= MEGABYTE.bytes() )
                {
                    return MEGABYTE;
                }
                else if ( size >= KILOBYTE.bytes() )
                {
                    return KILOBYTE;
                }
                else
                {
                    return BYTE;
                }
            }
        }

        private DecimalFormat smallFormat;
        private DecimalFormat largeFormat;

        public FileSizeFormat( Locale locale )
        {
            smallFormat = new DecimalFormat( "#0.0", new DecimalFormatSymbols( locale ) );
            largeFormat = new DecimalFormat( "###0", new DecimalFormatSymbols( locale ) );
        }

        public String format( long size )
        {
            return format( size, null );
        }

        public String format( long size, ScaleUnit unit )
        {
            return format( size, unit, false );
        }

        public String format( long size, ScaleUnit unit, boolean omitSymbol )
        {
            Validate.isTrue( size >= 0, "File size cannot be negative: %s", size );

            if ( unit == null )
            {
                unit = ScaleUnit.getScaleUnit( size );
            }

            double scaledSize = (double) size / unit.bytes();
            String scaledSymbol = " " + unit.symbol();

            if ( omitSymbol )
            {
                scaledSymbol = "";
            }

            if ( unit == ScaleUnit.BYTE )
            {
                return largeFormat.format( size ) + scaledSymbol;
            }

            if ( scaledSize < 0.05 || scaledSize >= 10.0 )
            {
                return largeFormat.format( scaledSize ) + scaledSymbol;
            }
            else
            {
                return smallFormat.format( scaledSize ) + scaledSymbol;
            }
        }

        public String formatProgress( long progressedSize, long size )
        {
            Validate.isTrue( progressedSize >= 0L, "Progressed file size cannot be negative: %s", progressedSize );
            Validate.isTrue( size >= 0L && progressedSize <= size || size < 0L,
                "Progressed file size cannot be bigger than size: %s > %s", progressedSize, size );

            if ( size >= 0 && progressedSize != size )
            {
                ScaleUnit unit = ScaleUnit.getScaleUnit( size );
                String formattedProgressedSize = format( progressedSize, unit, true );
                String formattedSize = format( size, unit );

                return formattedProgressedSize + "/" + formattedSize;
            }
            else
            {
                return format( progressedSize );
            }
        }
    }

    protected PrintStream out;

    protected AbstractMavenTransferListener( PrintStream out )
    {
        this.out = out;
    }

    @Override
    public void transferInitiated( TransferEvent event )
    {
        String type = event.getRequestType() == TransferEvent.RequestType.PUT ? "Uploading" : "Downloading";
        TransferResource resource = event.getResource();
        String repositoryId = event.getSession().getLocalRepository().getId();
        StringBuilder message = new StringBuilder(type + " from " + repositoryId);
        message.append(": ");
        message.append(resource.getRepositoryUrl() + resource.getResourceName());
        out.println(message);
    }
    
    @Override
    public void transferCorrupted( TransferEvent event )
        throws TransferCancelledException
    {
        TransferResource resource = event.getResource();
        String repositoryId = event.getSession().getLocalRepository().getId();
        out.println( "[WARNING] " + event.getException().getMessage() + " from " + repositoryId + " for " + resource.getRepositoryUrl()
            + resource.getResourceName() );
    }

    @Override
    public void transferSucceeded( TransferEvent event )
    {
        TransferResource resource = event.getResource();
        long contentLength = event.getTransferredBytes();

        FileSizeFormat format = new FileSizeFormat( Locale.ENGLISH );
        String type = ( event.getRequestType() == TransferEvent.RequestType.PUT ? "Uploaded" : "Downloaded" );
        String repositoryId = event.getSession().getLocalRepository().getId();
        String len = format.format( contentLength );

        String throughput = "";
        long duration = System.currentTimeMillis() - resource.getTransferStartTime();
        if ( duration > 0L )
        {
            double bytesPerSecond = contentLength / ( duration / 1000.0 );
            throughput = " at " + format.format( (long) bytesPerSecond ) + "/s";
        }
        
        StringBuilder message = new StringBuilder(type + " from " + repositoryId);
        message.append(": " + resource.getRepositoryUrl() + resource.getResourceName() + " (" + len + throughput + ")");
        out.println(message);
    }

}
