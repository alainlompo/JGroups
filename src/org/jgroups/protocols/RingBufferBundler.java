package org.jgroups.protocols;

/**
 * A bundler based on {@link org.jgroups.util.RingBuffer}
 * @author Bela Ban
 * @since  4.0
 */

import org.jgroups.Address;
import org.jgroups.Global;
import org.jgroups.Message;
import org.jgroups.util.RingBuffer;
import org.jgroups.util.Util;

import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;

/**
 * This bundler adds all (unicast or multicast) messages to a queue until max size has been exceeded, but does send
 * messages immediately when no other messages are available. https://issues.jboss.org/browse/JGRP-1540
 */
public class RingBufferBundler extends BaseBundler implements Runnable {
    protected RingBuffer<Message>         rb;
    protected volatile Thread             bundler_thread;
    protected volatile boolean            running=true;
    protected int                         num_spins=40; // number of times we call Thread.yield before acquiring the lock (0 disables)
    protected static final String         THREAD_NAME="RingBufferBundler";
    protected BiConsumer<Integer,Integer> wait_strategy=SPIN_PARK;
    protected int                         capacity;

    protected static final BiConsumer<Integer,Integer> SPIN=(it,spins) -> {;};
    protected static final BiConsumer<Integer,Integer> YIELD=(it,spins) -> Thread.yield();
    protected static final BiConsumer<Integer,Integer> PARK=(it,spins) -> LockSupport.parkNanos(1);
    protected static final BiConsumer<Integer,Integer> SPIN_PARK=(it, spins) -> {
        if(it < spins/10)
            ; // spin for the first 10% of all iterations, then switch to park()
        LockSupport.parkNanos(1);
    };
    protected static final BiConsumer<Integer,Integer> SPIN_YIELD=(it, spins) -> {
        if(it < spins/10)
            ;           // spin for the first 10% of the total number of iterations
        Thread.yield(); //, then switch to yield()
    };


    public RingBufferBundler() {
    }

    protected RingBufferBundler(RingBuffer<Message> rb) {
        this.rb=rb;
        this.capacity=rb.capacity();
    }

    public RingBufferBundler(int capacity) {
        this(new RingBuffer<>(Message.class, assertPositive(capacity, "bundler capacity cannot be " + capacity)));
    }

    public RingBuffer<Message> buf()                     {return rb;}
    public Thread              getThread()               {return bundler_thread;}
    public int                 getBufferSize()           {return rb.size();}
    public int                 numSpins()                {return num_spins;}
    public RingBufferBundler   numSpins(int n)           {num_spins=n; return this;}
    public String              waitStrategy()            {return print(wait_strategy);}
    public RingBufferBundler   waitStrategy(String st)   {wait_strategy=createWaitStrategy(st, YIELD); return this;}


    public void init(TP transport) {
        super.init(transport);
        if(rb == null) {
            rb=new RingBuffer<>(Message.class, assertPositive(transport.getBundlerCapacity(), "bundler capacity cannot be " + transport.getBundlerCapacity()));
            this.capacity=rb.capacity();
        }
    }

    public synchronized void start() {
        if(running)
            stop();
        bundler_thread=transport.getThreadFactory().newThread(this, THREAD_NAME);
        running=true;
        bundler_thread.start();
    }

    public synchronized void stop() {
        _stop(true);
    }

    public synchronized void stopAndFlush() {
        _stop(false);
    }

    public void send(Message msg) throws Exception {
        if(running)
            rb.put(msg);
    }

    public void run() {
        while(running) {
            try {
                readMessages();
            }
            catch(Throwable t) {
            }
        }
    }


    protected void readMessages() throws InterruptedException {
        int available_msgs=rb.waitForMessages(num_spins, wait_strategy);
        int read_index=rb.readIndexLockless();
        Message[] buf=rb.buf();
        sendBundledMessages(buf, read_index, available_msgs);
        rb.publishReadIndex(available_msgs);
    }



    /** Read and send messages in range [read-index .. read-index+available_msgs-1] */
    public void sendBundledMessages(final Message[] buf, final int read_index, final int available_msgs) {
        int       max_bundle_size=transport.getMaxBundleSize();
        byte[]    cluster_name=transport.cluster_name.chars();
        int       start=read_index;
        final int end=index(start + available_msgs-1); // index of the last message to be read

        for(;;) {
            Message msg=buf[start];
            if(msg == null) {
                if(start == end)
                    break;
                start=advance(start);
                continue;
            }

            Address dest=msg.dest();
            try {
                output.position(0);
                Util.writeMessageListHeader(dest, msg.src(), cluster_name, 1, output, dest == null);

                // remember the position at which the number of messages (an int) was written, so we can later set the
                // correct value (when we know the correct number of messages)
                int size_pos=output.position() - Global.INT_SIZE;
                int num_msgs=marshalMessagesToSameDestination(dest, buf, start, end, max_bundle_size);
                int current_pos=output.position();
                output.position(size_pos);
                output.writeInt(num_msgs);
                output.position(current_pos);
                transport.doSend(output.buffer(), 0, output.position(), dest);
            }
            catch(Exception ex) {
                log.error("failed to send message(s)", ex);
            }

            if(start == end)
                break;
            start=advance(start);
        }
    }

    // Iterate through the following messages and find messages to the same destination (dest) and write them to output
    protected int marshalMessagesToSameDestination(Address dest, Message[] buf,
                                                   int start_index, final int end_index, int max_bundle_size) throws Exception {
        int num_msgs=0, bytes=0;
        for(;;) {
            Message msg=buf[start_index];
            if(msg != null && Objects.equals(dest, msg.dest())) {
                long size=msg.size();
                if(bytes + size > max_bundle_size)
                    break;
                bytes+=size;
                num_msgs++;
                buf[start_index]=null;
                msg.writeToNoAddrs(msg.src(), output, transport.getId());
            }
            if(start_index == end_index)
                break;
            start_index=advance(start_index);
        }
        return num_msgs;
    }

    protected final int advance(int index) {return index+1 == capacity? 0 : index+1;}
    protected final int index(int idx)     {return idx & (capacity-1);}    // fast equivalent to %

    protected void _stop(boolean clear_queue) {
        running=false;
        Thread tmp=bundler_thread;
        bundler_thread=null;
        if(tmp != null) {
            tmp.interrupt();
            if(tmp.isAlive()) {
                try {tmp.join(500);} catch(InterruptedException e) {}
            }
        }
        if(clear_queue)
            rb.clear();
    }

    protected static String print(BiConsumer<Integer,Integer> wait_strategy) {
        if(wait_strategy      == null)            return null;
        if(wait_strategy      == SPIN)            return "spin";
        else if(wait_strategy == YIELD)           return "yield";
        else if(wait_strategy == PARK)            return "park";
        else if(wait_strategy == SPIN_PARK)       return "spin-park";
        else if(wait_strategy == SPIN_YIELD)      return "spin-yield";
        else return wait_strategy.getClass().getSimpleName();
    }

    protected BiConsumer<Integer,Integer> createWaitStrategy(String st, BiConsumer<Integer,Integer> default_wait_strategy) {
        if(st == null) return default_wait_strategy != null? default_wait_strategy : null;
        switch(st) {
            case "spin":            return wait_strategy=SPIN;
            case "yield":           return wait_strategy=YIELD;
            case "park":            return wait_strategy=PARK;
            case "spin_park":
            case "spin-park":       return wait_strategy=SPIN_PARK;
            case "spin_yield":
            case "spin-yield":      return wait_strategy=SPIN_YIELD;
            default:
                try {
                    Class<BiConsumer<Integer,Integer>> clazz=Util.loadClass(st, this.getClass());
                    return clazz.newInstance();
                }
                catch(Throwable t) {
                    log.error("failed creating wait_strategy " + st, t);
                    return default_wait_strategy != null? default_wait_strategy : null;
                }
        }
    }

    protected static int assertPositive(int value, String message) {
        if(value <= 0) throw new IllegalArgumentException(message);
        return value;
    }
}