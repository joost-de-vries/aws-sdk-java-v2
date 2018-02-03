/*
 * Copyright 2010-2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.core.async;

import java.nio.ByteBuffer;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Signals a single byte buffer element;
 * Can be subscribed to multiple times;
 */
class SingleByteArrayAsyncRequestProvider implements AsyncRequestProvider {

  private final byte[] bytes;

  SingleByteArrayAsyncRequestProvider(byte[] bytes) {
    this.bytes = bytes.clone();
  }

  @Override
  public long contentLength() {
    return bytes.length;
  }

  @Override
  public void subscribe(Subscriber<? super ByteBuffer> s) {
    // As per rule 2.13, we need to throw a `java.lang.NullPointerException` if the `Subscription` is `null`
    if (s == null) throw new NullPointerException("Subscription MUST NOT be null.");

    // As per 2.13, this method must return normally (i.e. not throw).
    try {
      s.onSubscribe(
          new Subscription() {
            boolean done = false;
            @Override
            public void request(long n) {
              if (n > 0) {
                if (!done) {
                  s.onNext(ByteBuffer.wrap(bytes));
                  done = true;
                  s.onComplete();
                }
              } else {
                s.onError(new IllegalArgumentException("§3.9: non-positive requests are not allowed!"));
              }
            }

            @Override
            public void cancel() {
            }
          }
      );
    } catch (Throwable ex) {
      new IllegalStateException(s + " violated the Reactive Streams rule 2.13 " +
          "by throwing an exception from onSubscribe.", ex)
          // When onSubscribe fails this way, we don't know what state the
          // s is thus calling onError may cause more crashes.
          .printStackTrace();
    }
  }
}
