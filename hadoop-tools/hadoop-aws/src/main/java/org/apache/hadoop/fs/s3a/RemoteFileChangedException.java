/*
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

package org.apache.hadoop.fs.s3a;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.fs.PathIOException;

/**
 * Indicates the S3 object is out of sync with the expected version.  Thrown in
 * cases such as when the object is updated while an {@link S3AInputStream} is
 * open, or when a file to be renamed disappeared during the operation.
 */
@SuppressWarnings("serial")
@InterfaceAudience.Public
@InterfaceStability.Unstable
public class RemoteFileChangedException extends PathIOException {

  public static final String PRECONDITIONS_FAILED =
      "Constraints of request were unsatisfiable";

  /**
   * The file disappeaded during a rename between LIST and COPY.
   */
  public static final String FILE_NOT_FOUND_SINGLE_ATTEMPT =
      "File to rename disappeared during the rename operation.";

  /**
   * Constructs a RemoteFileChangedException.
   *
   * @param path the path accessed when the change was detected
   * @param operation the operation (e.g. open, re-open) performed when the
   * change was detected
   * @param message a message providing more details about the condition
   */
  public RemoteFileChangedException(String path,
      String operation,
      String message) {
    super(path, message);
    setOperation(operation);
  }

  /**
   * Constructs a RemoteFileChangedException.
   *
   * @param path the path accessed when the change was detected
   * @param operation the operation (e.g. open, re-open) performed when the
   * change was detected
   * @param message a message providing more details about the condition
   * @param cause inner cause.
   */
  public RemoteFileChangedException(String path,
      String operation,
      String message,
      Throwable cause) {
    super(path, message, cause);
    setOperation(operation);
  }
}
