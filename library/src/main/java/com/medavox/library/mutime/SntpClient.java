/*
 * Original work Copyright (C) 2008 The Android Open Source Project
 * Modified work Copyright (C) 2016, Instacart
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.medavox.library.mutime;

import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Simple SNTP client class for retrieving network time.
 */
class SntpClient {
    private static final String TAG = SntpClient.class.getSimpleName();

    private static final int INDEX_VERSION = 0;
    private static final int INDEX_ROOT_DELAY = 4;
    private static final int INDEX_ROOT_DISPERSION = 8;
    private static final int INDEX_ORIGINATE_TIME = 24;
    private static final int INDEX_RECEIVE_TIME = 32;
    private static final int INDEX_TRANSMIT_TIME = 40;


    static final int NTP_PORT = 123;
    static final int NTP_MODE = 3;
    static final int NTP_VERSION = 3;
    static final int NTP_PACKET_SIZE = 48;

    // 70 years plus 17 leap days
    private static final long OFFSET_1900_TO_1970 = ((365L * 70L) + 17L) * 24L * 60L * 60L;

    /**
     * Sends an NTP request to the given host and processes the response.
     *
     * @param ntpHost host name of the server.
     * @param timeout network timeout in milliseconds.
     */
    synchronized TimeData requestTime(String ntpHost,
                                       float rootDelayMax,
                                       float rootDispersionMax,
                                       int serverResponseDelayMax,
                                       int timeout) throws IOException {
        //Log.d(TAG, "requesting the time from "+ntpHost+"...");
        DatagramSocket socket = null;

        try {
            InetAddress address = InetAddress.getByName(ntpHost);
            byte[] buffer = new byte[NTP_PACKET_SIZE];
            DatagramPacket request = new DatagramPacket(buffer, buffer.length, address, NTP_PORT);

            // set mode and version
            // mode is in low 3 bits of first byte
            // version is in bits 3-5 of first byte
            buffer[INDEX_VERSION] = NTP_MODE | (NTP_VERSION << 3);

            //initialise socket
            socket = new DatagramSocket();
            socket.setSoTimeout(timeout);

            // get current time and write it to the request packet
            long clockAtRequest = System.currentTimeMillis();
            long uptimeAtRequest = SystemClock.elapsedRealtime();
            writeTimeStamp(buffer, INDEX_TRANSMIT_TIME, clockAtRequest);

            socket.send(request);

            // read the response
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);

            socket.receive(response);
            long uptimeAtResponse = SystemClock.elapsedRealtime();//=clockAtSntpTime
            long clockAtResponse = System.currentTimeMillis();//=uptimeOffset

            // extract the results
            // See here for the algorithm used:
            // https://en.wikipedia.org/wiki/Network_Time_Protocol#Clock_synchronization_algorithm

            //long originateTime = readTimeStamp(buffer, INDEX_ORIGINATE_TIME);     // T0
            long receiveTime = readTimeStamp(buffer, INDEX_RECEIVE_TIME);         // T1
            long transmitTime = readTimeStamp(buffer, INDEX_TRANSMIT_TIME);       // T2

            //long responseTime = clockAtRequest + (uptimeAtResponse - uptimeAtRequest);//T3
            //long differenceBetweenCalculatedAndClock = responseTime - clockAtResponse;
            //Log.v(TAG, "difference between calculated responseTime and equivalent System Clock value: "+
            //differenceBetweenCalculatedAndClock);

            // -----------------------------------------------------------------------------------
            // check validity of response

            double rootDelay = doubleMillis(read(buffer, INDEX_ROOT_DELAY));
            if (rootDelay > rootDelayMax) {
                throw new InvalidNtpResponseException(
                        "Invalid response from NTP server. %s violation. %f [actual] > %f [expected]",
                        "root_delay",
                        (float) rootDelay,
                        rootDelayMax);
            }

            double rootDispersion = doubleMillis(read(buffer, INDEX_ROOT_DISPERSION));
            if (rootDispersion > rootDispersionMax) {
                throw new InvalidNtpResponseException(
                        "Invalid response from NTP server. %s violation. %f [actual] > %f [expected]",
                        "root_dispersion",
                        (float) rootDispersion,
                        rootDispersionMax);
            }

            final byte mode = (byte) (buffer[0] & 0x7);
            if (mode != 4 && mode != 5) {
                throw new InvalidNtpResponseException("untrusted mode value for MuTime: " + mode);
            }

            final int stratum = buffer[1] & 0xff;
            if (stratum < 1 || stratum > 15) {
                throw new InvalidNtpResponseException("untrusted stratum value for MuTime: " + stratum);
            }

            final byte leap = (byte) ((buffer[0] >> 6) & 0x3);
            if (leap == 3) {
                throw new InvalidNtpResponseException("unsynchronized server responded for MuTime");
            }

            Log.wtf("SNTP","CLOCK AT RESPONSE : " + clockAtResponse);
            Log.wtf("SNTP","CLOCK AT REQUEST : " + clockAtRequest);
            Log.wtf("SNTP", "TRANSMIT TIME : " + transmitTime);
            Log.wtf("SNTP", "RECEIVE TIME : " + receiveTime);

            long roundTripDelay = (clockAtResponse - clockAtRequest) - (transmitTime - receiveTime);
            long delay = Math.abs(roundTripDelay);
            if (delay >= serverResponseDelayMax) {
                throw new InvalidNtpResponseException(
                    "%s too large for comfort; %f [actual] >= %f [max]",
                    "server_response_delay",
                    (float) delay,
                    serverResponseDelayMax);
            }

            long timeElapsedSinceRequest = Math.abs(clockAtRequest - System.currentTimeMillis());
            if (timeElapsedSinceRequest >= 10_000) {
                throw new InvalidNtpResponseException("Request was sent more than 10 seconds ago " +
                                                            timeElapsedSinceRequest);
            }

            // -----------------------------------------------------------------------------------

            //response data is valid, send time info from response
            //TimeData td = new TimeData.Builder().roundTripDelay()
            long clockOffset = ((receiveTime - clockAtRequest) + (transmitTime - clockAtResponse)) / 2;
            long uptimeOffset = ((receiveTime - uptimeAtRequest) + (transmitTime - uptimeAtResponse)) / 2;

            TimeData td = new TimeData.Builder()
                    .systemClockOffset(clockOffset)
                    .uptimeOffset(uptimeOffset)
                    .roundTripDelay(roundTripDelay)
                    .build();
            //Log.i(TAG, "---- SNTP successful response from " + ntpHost);
            return td;
        } catch (Exception e) {
            Log.e(TAG, "SNTP request failed for " + ntpHost+": "+e);
            //e.printStackTrace();
            throw e;
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    //--------------------------------------------------------------------------------------------

    /**
     * Reads an unsigned 32 bit big endian number
     * from the given offset in the buffer
     *
     * @return 4 bytes as a 32-bit long (unsigned big endian)
     */
    private long read(byte[] buffer, int offset) {
        byte b0 = buffer[offset];
        byte b1 = buffer[offset + 1];
        byte b2 = buffer[offset + 2];
        byte b3 = buffer[offset + 3];

        return ((long) ui(b0) << 24) +
               ((long) ui(b1) << 16) +
               ((long) ui(b2) << 8) +
               (long) ui(b3);
    }

    /**
     * Writes system time (milliseconds since January 1, 1970) as an NTP time stamp
     * as defined in RFC-1305
     * at the given offset in the buffer.
     */
    private void writeTimeStamp(byte[] buffer, int offset, long time) {
        long seconds = time / 1000L;
        long milliseconds = time - seconds * 1000L;

        // consider offset for number of seconds
        // between Jan 1, 1900 (NTP epoch) and Jan 1, 1970 (Java epoch)
        seconds += OFFSET_1900_TO_1970;

        // write seconds in big endian format
        buffer[offset++] = (byte)(seconds >> 24);
        buffer[offset++] = (byte)(seconds >> 16);
        buffer[offset++] = (byte)(seconds >> 8);
        buffer[offset++] = (byte)(seconds >> 0);

        long fraction = milliseconds * 0x100000000L / 1000L;

        // write fraction in big endian format
        buffer[offset++] = (byte)(fraction >> 24);
        buffer[offset++] = (byte)(fraction >> 16);
        buffer[offset++] = (byte)(fraction >> 8);

        // low order bits should be random data
        buffer[offset++] = (byte) (Math.random() * 255.0);
    }

    /**
     * @param offset offset index in buffer to start reading from
     * @return NTP timestamp in Java epoch
     */
    private long readTimeStamp(byte[] buffer, int offset) {
        long seconds = read(buffer, offset);
        long fraction = read(buffer, offset + 4);

        return ((seconds - OFFSET_1900_TO_1970) * 1000) + ((fraction * 1000L) / 0x100000000L);
    }

    /***
     * Convert (signed) byte to an unsigned int
     *
     * Java only has signed types so we have to do
     * more work to get unsigned ops
     *
     * @param b input byte
     * @return unsigned int value of byte
     */
    private int ui(byte b) {
        return b & 0xFF;
    }

    /**
     * Used for root delay and dispersion
     *
     * According to the NTP spec, they are in the NTP Short format
     * viz. signed 16.16 fixed point
     *
     * @param fix signed fixed point number
     * @return as a double in milliseconds
     */
    //todo: replace floating-point arithmetic
    private double doubleMillis(long fix) {
        return fix / 65.536D;
    }

    interface SntpResponseListener {
        void onSntpTimeData(TimeData data);
    }
}
