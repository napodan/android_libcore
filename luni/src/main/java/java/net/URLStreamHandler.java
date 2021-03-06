/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package java.net;

import java.io.IOException;
import libcore.util.Objects;
import org.apache.harmony.luni.util.URLUtil;

/**
 * The abstract class {@code URLStreamHandler} is the base for all classes which
 * can handle the communication with a URL object over a particular protocol
 * type.
 */
public abstract class URLStreamHandler {
    /**
     * Establishes a new connection to the resource specified by the URL {@code
     * u}. Since different protocols also have unique ways of connecting, it
     * must be overwritten by the subclass.
     *
     * @param u
     *            the URL to the resource where a connection has to be opened.
     * @return the opened URLConnection to the specified resource.
     * @throws IOException
     *             if an I/O error occurs during opening the connection.
     */
    protected abstract URLConnection openConnection(URL u) throws IOException;

    /**
     * Establishes a new connection to the resource specified by the URL {@code
     * u} using the given {@code proxy}. Since different protocols also have
     * unique ways of connecting, it must be overwritten by the subclass.
     *
     * @param u
     *            the URL to the resource where a connection has to be opened.
     * @param proxy
     *            the proxy that is used to make the connection.
     * @return the opened URLConnection to the specified resource.
     * @throws IOException
     *             if an I/O error occurs during opening the connection.
     * @throws IllegalArgumentException
     *             if any argument is {@code null} or the type of proxy is
     *             wrong.
     * @throws UnsupportedOperationException
     *             if the protocol handler doesn't support this method.
     */
    protected URLConnection openConnection(URL u, Proxy proxy) throws IOException {
        throw new UnsupportedOperationException();
    }

    /**
     * Parses the clear text URL in {@code str} into a URL object. URL strings
     * generally have the following format:
     * <p>
     * http://www.company.com/java/file1.java#reference
     * <p>
     * The string is parsed in HTTP format. If the protocol has a different URL
     * format this method must be overridden.
     *
     * @param u
     *            the URL to fill in the parsed clear text URL parts.
     * @param str
     *            the URL string that is to be parsed.
     * @param start
     *            the string position from where to begin parsing.
     * @param end
     *            the string position to stop parsing.
     * @see #toExternalForm
     * @see URL
     */
    protected void parseURL(URL u, String str, int start, int end) {
        // For compatibility, refer to Harmony-2941
        if (str.startsWith("//", start)
                && str.indexOf('/', start + 2) == -1
                && end <= Integer.MIN_VALUE + 1) {
            throw new StringIndexOutOfBoundsException(end - 2 - start);
        }
        if (end < start) {
            if (this != u.strmHandler) {
                throw new SecurityException();
            }
            return;
        }
        String parseString = "";
        if (start < end) {
            parseString = str.substring(start, end);
        }
        end -= start;
        int fileIdx = 0;

        // Default is to use info from context
        String host = u.getHost();
        int port = u.getPort();
        String ref = u.getRef();
        String file = u.getPath();
        String query = u.getQuery();
        String authority = u.getAuthority();
        String userInfo = u.getUserInfo();

        int refIdx = parseString.indexOf('#', 0);
        if (parseString.startsWith("//")) {
            int hostIdx = 2, portIdx = -1;
            port = -1;
            fileIdx = parseString.indexOf('/', hostIdx);
            int questionMarkIndex = parseString.indexOf('?', hostIdx);
            if ((questionMarkIndex != -1)
                    && ((fileIdx == -1) || (fileIdx > questionMarkIndex))) {
                fileIdx = questionMarkIndex;
            }
            if (fileIdx == -1) {
                fileIdx = end;
                // Use default
                file = "";
            }
            int hostEnd = fileIdx;
            if (refIdx != -1 && refIdx < fileIdx) {
                hostEnd = refIdx;
            }
            int userIdx = parseString.lastIndexOf('@', hostEnd);
            authority = parseString.substring(hostIdx, hostEnd);
            if (userIdx > -1) {
                userInfo = parseString.substring(hostIdx, userIdx);
                hostIdx = userIdx + 1;
            }

            portIdx = parseString.indexOf(':', userIdx == -1 ? hostIdx
                    : userIdx);
            int endOfIPv6Addr = parseString.indexOf(']');
            // if there are square braces, ie. IPv6 address, use last ':'
            if (endOfIPv6Addr != -1) {
                try {
                    if (parseString.length() > endOfIPv6Addr + 1) {
                        char c = parseString.charAt(endOfIPv6Addr + 1);
                        if (c == ':') {
                            portIdx = endOfIPv6Addr + 1;
                        } else {
                            portIdx = -1;
                        }
                    } else {
                        portIdx = -1;
                    }
                } catch (Exception e) {
                    // Ignored
                }
            }

            if (portIdx == -1 || portIdx > fileIdx) {
                host = parseString.substring(hostIdx, hostEnd);
            } else {
                host = parseString.substring(hostIdx, portIdx);
                String portString = parseString.substring(portIdx + 1, hostEnd);
                if (portString.length() == 0) {
                    port = -1;
                } else {
                    port = Integer.parseInt(portString);
                }
            }
        }

        if (refIdx > -1) {
            ref = parseString.substring(refIdx + 1, end);
        }
        int fileEnd = (refIdx == -1 ? end : refIdx);

        int queryIdx = parseString.lastIndexOf('?', fileEnd);
        boolean canonicalize = false;
        if (queryIdx > -1) {
            query = parseString.substring(queryIdx + 1, fileEnd);
            if (queryIdx == 0 && file != null) {
                if (file.isEmpty()) {
                    file = "/";
                } else if (file.startsWith("/")) {
                    canonicalize = true;
                }
                int last = file.lastIndexOf('/') + 1;
                file = file.substring(0, last);
            }
            fileEnd = queryIdx;
        } else
        // Don't inherit query unless only the ref is changed
        if (refIdx != 0) {
            query = null;
        }

        if (fileIdx > -1) {
            if (fileIdx < end && parseString.charAt(fileIdx) == '/') {
                file = parseString.substring(fileIdx, fileEnd);
            } else if (fileEnd > fileIdx) {
                if (file == null) {
                    file = "";
                } else if (file.isEmpty()) {
                    file = "/";
                } else if (file.startsWith("/")) {
                    canonicalize = true;
                }
                int last = file.lastIndexOf('/') + 1;
                if (last == 0) {
                    file = parseString.substring(fileIdx, fileEnd);
                } else {
                    file = file.substring(0, last)
                            + parseString.substring(fileIdx, fileEnd);
                }
            }
        }
        if (file == null) {
            file = "";
        }

        if (host == null) {
            host = "";
        }

        if (canonicalize) {
            // modify file if there's any relative referencing
            file = URLUtil.canonicalizePath(file);
        }

        setURL(u, u.getProtocol(), host, port, authority, userInfo, file,
                query, ref);
    }

    /**
     * Sets the fields of the URL {@code u} to the values of the supplied
     * arguments.
     *
     * @param u
     *            the non-null URL object to be set.
     * @param protocol
     *            the protocol.
     * @param host
     *            the host name.
     * @param port
     *            the port number.
     * @param file
     *            the file component.
     * @param ref
     *            the reference.
     * @deprecated use setURL(URL, String String, int, String, String, String,
     *             String, String) instead.
     */
    @Deprecated
    protected void setURL(URL u, String protocol, String host, int port,
            String file, String ref) {
        if (this != u.strmHandler) {
            throw new SecurityException();
        }
        u.set(protocol, host, port, file, ref);
    }

    /**
     * Sets the fields of the URL {@code u} to the values of the supplied
     * arguments.
     *
     * @param u
     *            the non-null URL object to be set.
     * @param protocol
     *            the protocol.
     * @param host
     *            the host name.
     * @param port
     *            the port number.
     * @param authority
     *            the authority.
     * @param userInfo
     *            the user info.
     * @param file
     *            the file component.
     * @param query
     *            the query.
     * @param ref
     *            the reference.
     */
    protected void setURL(URL u, String protocol, String host, int port,
            String authority, String userInfo, String file, String query,
            String ref) {
        if (this != u.strmHandler) {
            throw new SecurityException();
        }
        u.set(protocol, host, port, authority, userInfo, file, query, ref);
    }

    /**
     * Returns the clear text representation of a given URL using HTTP format.
     *
     * @param url
     *            the URL object to be converted.
     * @return the clear text representation of the specified URL.
     * @see #parseURL
     * @see URL#toExternalForm()
     */
    protected String toExternalForm(URL url) {
        StringBuilder answer = new StringBuilder();
        answer.append(url.getProtocol());
        answer.append(':');
        String authority = url.getAuthority();
        if (authority != null && authority.length() > 0) {
            answer.append("//");
            answer.append(url.getAuthority());
        }

        String file = url.getFile();
        String ref = url.getRef();
        if (file != null) {
            answer.append(file);
        }
        if (ref != null) {
            answer.append('#');
            answer.append(ref);
        }
        return answer.toString();
    }

    /**
     * Compares two URL objects whether they represent the same URL. Two URLs
     * are equal if they have the same file, host, port, protocol, query, and
     * reference components.
     *
     * @param url1
     *            the first URL to compare.
     * @param url2
     *            the second URL to compare.
     * @return {@code true} if the URLs are the same, {@code false} otherwise.
     * @see #hashCode
     */
    protected boolean equals(URL url1, URL url2) {
        if (!sameFile(url1, url2)) {
            return false;
        }
        return Objects.equal(url1.getRef(), url2.getRef())
                && Objects.equal(url1.getQuery(), url2.getQuery());
    }

    /**
     * Returns the default port of the protocol used by the handled URL. The
     * current implementation returns always {@code -1}.
     *
     * @return the appropriate default port number of the protocol.
     */
    protected int getDefaultPort() {
        return -1;
    }

    /**
     * Returns the host address of the given URL.
     *
     * @param url
     *            the URL object where to read the host address from.
     * @return the host address of the specified URL.
     */
    protected InetAddress getHostAddress(URL url) {
        try {
            String host = url.getHost();
            if (host == null || host.length() == 0) {
                return null;
            }
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * Returns the hashcode value for the given URL object.
     *
     * @param url
     *            the URL to determine the hashcode.
     * @return the hashcode of the given URL.
     */
    protected int hashCode(URL url) {
        return toExternalForm(url).hashCode();
    }

    /**
     * Compares two URL objects whether they refer to the same host.
     *
     * @param a the first URL to be compared.
     * @param b the second URL to be compared.
     * @return {@code true} if both URLs refer to the same host, {@code false}
     *         otherwise.
     */
    protected boolean hostsEqual(URL a, URL b) {
        /*
         * URLs with the same case-insensitive host name have equal hosts
         */
        String aHost = getHost(a);
        String bHost = getHost(b);
        if (aHost != null && aHost.equalsIgnoreCase(bHost)) {
            return true;
        }

        /*
         * Call out to DNS to resolve the host addresses. If this succeeds for
         * both addresses and both addresses yield the same InetAddress, report
         * equality.
         *
         * Although it's consistent with historical behavior of the RI, this
         * approach is fundamentally broken. In particular, acting upon this
         * result is bogus because a single server may serve content for many
         * unrelated host names.
         */
        InetAddress aResolved = getHostAddress(a);
        return aResolved != null && aResolved.equals(getHostAddress(b));
    }

    /**
     * Compares two URL objects whether they refer to the same file. In the
     * comparison included are the URL components protocol, host, port and file.
     *
     * @param url1
     *            the first URL to be compared.
     * @param url2
     *            the second URL to be compared.
     * @return {@code true} if both URLs refer to the same file, {@code false}
     *         otherwise.
     */
    protected boolean sameFile(URL url1, URL url2) {
        return Objects.equal(url1.getProtocol(), url2.getProtocol())
                && Objects.equal(url1.getFile(), url2.getFile())
                && hostsEqual(url1, url2)
                && url1.getEffectivePort() == url2.getEffectivePort();
    }

    /*
     * If the URL host is empty while protocal is file, the host is regarded as
     * localhost.
     */
    private static String getHost(URL url) {
        String host = url.getHost();
        if ("file".equals(url.getProtocol()) && host.isEmpty()) {
            host = "localhost";
        }
        return host;
    }
}
