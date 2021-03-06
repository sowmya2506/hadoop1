/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.fs;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.util.Preconditions;

/**
 * Representation of a URL connection to open InputStreams.
 */
@InterfaceAudience.Private
@InterfaceStability.Unstable
class FsUrlConnection extends URLConnection {
  private static final Logger LOG =
      LoggerFactory.getLogger(FsUrlConnection.class);

  private Configuration conf;

  private InputStream is;

  FsUrlConnection(Configuration conf, URL url) {
    super(url);
    Preconditions.checkArgument(conf != null, "null conf argument");
    Preconditions.checkArgument(url != null, "null url argument");
    this.conf = conf;
  }

  @Override
  public void connect() throws IOException {
    Preconditions.checkState(is == null, "Already connected");
    try {
      LOG.debug("Connecting to {}", url);
      URI uri = url.toURI();
      FileSystem fs = FileSystem.get(uri, conf);
      // URI#getPath returns null value if path contains relative path
      // i.e file:root/dir1/file1
      // So path can not be constructed from URI.
      // We can only use schema specific part in URI.
      // Uri#isOpaque return true if path is relative.
      if(uri.isOpaque() && uri.getScheme().equals("file")) {
        is = fs.open(new Path(uri.getSchemeSpecificPart()));
      } else {
        is = fs.open(new Path(uri));
      }
    } catch (URISyntaxException e) {
      throw new IOException(e.toString());
    }
  }

  @Override
  public InputStream getInputStream() throws IOException {
    if (is == null) {
      connect();
    }
    return is;
  }

}
