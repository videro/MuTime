package com.medavox.library.mutime;

import android.util.Log;

import org.reactivestreams.Publisher;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.FlowableEmitter;
import io.reactivex.FlowableOnSubscribe;
import io.reactivex.FlowableTransformer;
import io.reactivex.annotations.NonNull;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.functions.Predicate;
import io.reactivex.schedulers.Schedulers;

public class RxNtp {

    private static RxNtp RX_INSTANCE;
    private static final String TAG = RxNtp.class.getSimpleName();

    private int _retryCount = 50;

    public static RxNtp getInstance() {
        if (RX_INSTANCE == null) {
            RX_INSTANCE = new RxNtp();
        }
        return RX_INSTANCE;
    }

    public RxNtp withRetryCount(int retryCount) {
        _retryCount = retryCount;
        return this;
    }

    /**Initialize MuTime
     * See {@link #initializeNtp(String)} for details on working
     *
     * @return accurate NTP Date
     */
    public Flowable<Date> initializeRx(String ntpPoolAddress) {
        return initializeNtp(ntpPoolAddress).map(new Function<long[], Date>() {
            @Override
            public Date apply(long[] longs) throws Exception {
                return new Date(MuTime.now());
            }
        });
     }

    /**Initialize MuTime
     * A single NTP pool server is provided.
     * Using DNS we resolve that to multiple IP hosts
     * (See {@link #initializeNtp(InetAddress...)} for manually resolved IPs)
     *
     * Use this instead of {@link #initializeRx(String)} if you wish to also get additional info for
     * instrumentation/tracking actual NTP response data
     *
     * @param ntpPool NTP pool server e.g. time.apple.com, 0.us.pool.ntp.org
     * @return Observable of detailed long[] containing most important parts of the actual NTP response
     * See RESPONSE_INDEX_ prefixes in {@link SntpClient} for details
     */
    public Flowable<long[]> initializeNtp(String ntpPool) {
        return Flowable
              .just(ntpPool)
              .compose(resolveNtpPoolToIpAddresses)
              .compose(performNtpAlgorithm);
    }

    /**Initialize MuTime
     * Use this if you want to resolve the NTP Pool address to individual IPs yourself
     *
     * See https://github.com/instacart/truetime-android/issues/42
     * to understand why you may want to do something like this.
     *
     * @param resolvedNtpAddresses list of resolved IP addresses for an NTP request
     * @return Observable of detailed long[] containing most important parts of the actual NTP response
     * See RESPONSE_INDEX_ prefixes in {@link SntpClient} for details
     */
    public Flowable<long[]> initializeNtp(InetAddress... resolvedNtpAddresses) {
        return Flowable.fromArray(resolvedNtpAddresses)
               .compose(performNtpAlgorithm);
    }

    //----------------------------------------------------------------------------------------

    /**Takes in a pool of NTP addresses.
     * Against each IP host we issue a UDP call and retrieve the best response using the NTP algorithm
     */
    private FlowableTransformer<InetAddress, long[]> performNtpAlgorithm
    = new FlowableTransformer<InetAddress, long[]>() {
        @Override
        public Flowable<long[]> apply(Flowable<InetAddress> inetAddressObservable) {
            return inetAddressObservable
                  .map(new Function<InetAddress, String>() {
                      @Override
                      public String apply(InetAddress inetAddress) {
                          return inetAddress.getHostAddress();
                      }
                  })
                  .flatMap(bestResponseAgainstSingleIp(5))  // get best response from querying the ip 5 times
                  .take(5)                                  // take 5 of the best results
                  .toList()
                  .toFlowable()
                  .filter(new Predicate<List<long[]>>() {
                      @Override
                      public boolean test(List<long[]> longs) throws Exception {
                          return longs.size() > 0;
                      }
                  })
                  .map(filterMedianResponse)
                  .doOnNext(new Consumer<long[]>() {
                      @Override
                      public void accept(long[] ntpResponse) {
                          //SNTP_CLIENT.storeTimeOffset(ntpResponse);
                          MuTime.persistence.onSntpTimeData(SntpClient.fromLongArray(ntpResponse));
                      }
                  });
        }
    };

    private FlowableTransformer<String, InetAddress> resolveNtpPoolToIpAddresses
    = new FlowableTransformer<String, InetAddress>() {
        @Override
        public Publisher<InetAddress> apply(Flowable<String> ntpPoolFlowable) {
            return ntpPoolFlowable
                  .observeOn(Schedulers.io())
                  .flatMap(new Function<String, Flowable<InetAddress>>() {
                      @Override
                      public Flowable<InetAddress> apply(String ntpPoolAddress) {
                          try {
                              Log.d(TAG, "---- resolving ntpHost : " + ntpPoolAddress);
                              return Flowable.fromArray(InetAddress.getAllByName(ntpPoolAddress));
                          } catch (UnknownHostException e) {
                              return Flowable.error(e);
                          }
                      }
                  })
                .filter(new Predicate<InetAddress>() {
                        @Override
                        public boolean test(InetAddress inetAddress) throws Exception {
                            return isReachable(inetAddress);
                        }
                });
        }
    };

    /**Takes a single NTP host (as a String),
     * performs an SNTP request on it repeatCount number of times,
     * and returns the single result with the lowest round-trip delay*/
    private Function<String, Flowable<long[]>> bestResponseAgainstSingleIp(final int repeatCount) {
        return new Function<String, Flowable<long[]>>() {
            @Override
            public Flowable<long[]> apply(String singleIp) {
                return Flowable
                    .just(singleIp)
                    .repeat(repeatCount)
                    .flatMap(new Function<String, Flowable<long[]>>() {
                        @Override
                        public Flowable<long[]> apply(final String singleIpHostAddress) {
                            return Flowable.create(new FlowableOnSubscribe<long[]>() {
                                    @Override
                                    public void subscribe(@NonNull FlowableEmitter<long[]> o)
                                        throws Exception {

                                        Log.d(TAG,
                                            "---- requestTimeFromServer from: " + singleIpHostAddress);
                                        try {
                                            o.onNext(MuTime.requestTimeFromServer(singleIpHostAddress).send());
                                            o.onComplete();
                                        } catch (IOException e) {
                                            if (!o.isCancelled()) {
                                                o.onError(e);
                                            }
                                        }
                                    }
                                  }, BackpressureStrategy.BUFFER)
                                      .subscribeOn(Schedulers.io())
                                      .doOnError(new Consumer<Throwable>() {
                                          @Override
                                          public void accept(Throwable throwable) {
                                              Log.e(TAG, "---- Error requesting time", throwable);
                                          }
                                      })
                                      .retry(_retryCount);
                        }
                    })
                    .toList()
                    .toFlowable()
                    .map(filterLeastRoundTripDelay); // pick best response for each ip
            }
        };
    }

    /**Takes a List of NTP responses, and returns the one with the smallest round-trip delay*/
    private Function<List<long[]>, long[]> filterLeastRoundTripDelay
    = new Function<List<long[]>, long[]>() {
        @Override
        public long[] apply(List<long[]> responseTimeList) {
            Collections.sort(responseTimeList, new Comparator<long[]>() {
                @Override
                public int compare(long[] lhsParam, long[] rhsLongParam) {
                    long lhs = SntpClient.calcRoundTripDelay(lhsParam);
                    long rhs = SntpClient.calcRoundTripDelay(rhsLongParam);
                    return lhs < rhs ? -1 : (lhs == rhs ? 0 : 1);
                }
            });

            Log.d(TAG, "---- filterLeastRoundTrip: " + responseTimeList);

            return responseTimeList.get(0);
        }
    };

    /**Takes a list of NTP responses, and returns the one with the median value for clock offset*/
    private Function<List<long[]>, long[]> filterMedianResponse
    = new Function<List<long[]>, long[]>() {
        @Override
        public long[] apply(List<long[]> bestResponses) {
            Collections.sort(bestResponses, new Comparator<long[]>() {
                @Override
                public int compare(long[] lhsParam, long[] rhsParam) {
                    long lhs = SntpClient.calcClockOffset(lhsParam);
                    long rhs = SntpClient.calcClockOffset(rhsParam);
                    return lhs < rhs ? -1 : (lhs == rhs ? 0 : 1);
                }
            });

            Log.d(TAG, "---- bestResponse: " + Arrays.toString(bestResponses.get(bestResponses.size() / 2)));

            return bestResponses.get(bestResponses.size() / 2);
        }
    };

    private boolean isReachable(InetAddress addr) {
        try {
            Socket soc = new Socket();
            soc.connect(new InetSocketAddress(addr, 80), 5_000);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }
}
