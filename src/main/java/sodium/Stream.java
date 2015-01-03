package sodium;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class Stream<A> {
	private static final class ListenerImplementation<A> extends Listener {
		/**
		 * It's essential that we keep the listener alive while the caller holds
		 * the Listener, so that the finalizer doesn't get triggered.
		 */
		private final Stream<A> event;
		private final TransactionHandler<A> action;
		private final Node target;

		private ListenerImplementation(Stream<A> event, TransactionHandler<A> action, Node target) {
			this.event = event;
			this.action = action;
			this.target = target;
		}

		public void unlisten() {
		    synchronized (Transaction.listenersLock) {
                event.listeners.remove(action);
                event.node.unlinkTo(target);
            }
		}

		protected void finalize() throws Throwable {
			unlisten();
		}
	}

	protected final ArrayList<TransactionHandler<A>> listeners = new ArrayList<TransactionHandler<A>>();
	protected final List<Listener> finalizers = new ArrayList<Listener>();
	Node node = new Node(0L);
	protected final List<A> firings = new ArrayList<A>();

	/**
	 * An event that never fires.
	 */
	public Stream() {
	}

	protected Object[] sampleNow() { return null; }

	/**
	 * Listen for firings of this event. The returned Listener has an unlisten()
	 * method to cause the listener to be removed. This is the observer pattern.
     */
	public final Listener listen(final Handler<A> action) {
		return listen_(Node.NULL, new TransactionHandler<A>() {
			public void run(Transaction trans2, A a) {
				action.run(a);
			}
		});
	}

	final Listener listen_(final Node target, final TransactionHandler<A> action) {
		return Transaction.apply(new Lambda1<Transaction, Listener>() {
			public Listener apply(Transaction trans1) {
				return listen(target, trans1, action, false);
			}
		});
	}

	@SuppressWarnings("unchecked")
	final Listener listen(Node target, Transaction trans, TransactionHandler<A> action, boolean suppressEarlierFirings) {
        synchronized (Transaction.listenersLock) {
            if (node.linkTo(target))
                trans.toRegen = true;
            listeners.add(action);
        }
        trans.prioritized(target, new Handler<Transaction>() {
            public void run(Transaction trans2) {
                Object[] aNow = sampleNow();
                if (aNow != null) {    // In cases like value(), we start with an initial value.
                    for (int i = 0; i < aNow.length; i++)
                        action.run(trans, (A)aNow[i]);  // <-- unchecked warning is here
                }
                if (!suppressEarlierFirings) {
                    // Anything sent already in this transaction must be sent now so that
                    // there's no order dependency between send and listen.
                    for (A a : firings)
                        action.run(trans, a);
                }
            }
        });
		return new ListenerImplementation<A>(this, action, target);
	}

    /**
     * Transform the event's value according to the supplied function.
     */
	public final <B> Stream<B> map(final Lambda1<A,B> f)
	{
	    final Stream<A> ev = this;
	    final StreamSink<B> out = new StreamSink<B>() {
    		@SuppressWarnings("unchecked")
			@Override
            protected Object[] sampleNow()
            {
                Object[] oi = ev.sampleNow();
                if (oi != null) {
                    Object[] oo = new Object[oi.length];
                    for (int i = 0; i < oo.length; i++)
                        oo[i] = f.apply((A)oi[i]);
                    return oo;
                }
                else
                    return null;
            }
	    };
        Listener l = listen_(out.node, new TransactionHandler<A>() {
        	public void run(Transaction trans2, A a) {
	            out.send(trans2, f.apply(a));
	        }
        });
        return out.addCleanup(l);
	}

	/**
	 * Create a behavior with the specified initial value, that gets updated
     * by the values coming through the event. The 'current value' of the behavior
     * is notionally the value as it was 'at the start of the transaction'.
     * That is, state updates caused by event firings get processed at the end of
     * the transaction.
     */
	public final Cell<A> hold(final A initValue) {
		return Transaction.apply(new Lambda1<Transaction, Cell<A>>() {
			public Cell<A> apply(Transaction trans) {
			    return new Cell<A>(lastFiringOnly(trans), initValue);
			}
		});
	}

	final Cell<A> holdLazy(final Lambda0<A> initValue) {
		return Transaction.apply(new Lambda1<Transaction, Cell<A>>() {
			public Cell<A> apply(Transaction trans) {
			    return new LazyCell<A>(lastFiringOnly(trans), initValue);
			}
		});
	}

	/**
	 * Variant of snapshot that throws away the event's value and captures the behavior's.
	 */
	public final <B> Stream<B> snapshot(Cell<B> beh)
	{
	    return snapshot(beh, new Lambda2<A,B,B>() {
	    	public B apply(A a, B b) {
	    		return b;
	    	}
	    });
	}

	/**
	 * Sample the behavior at the time of the event firing. Note that the 'current value'
     * of the behavior that's sampled is the value as at the start of the transaction
     * before any state changes of the current transaction are applied through 'hold's.
     */
	public final <B,C> Stream<C> snapshot(final Cell<B> b, final Lambda2<A,B,C> f)
	{
	    final Stream<A> ev = this;
		final StreamSink<C> out = new StreamSink<C>() {
    		@SuppressWarnings("unchecked")
			@Override
            protected Object[] sampleNow()
            {
                Object[] oi = ev.sampleNow();
                if (oi != null) {
                    Object[] oo = new Object[oi.length];
                    for (int i = 0; i < oo.length; i++)
                        oo[i] = f.apply((A)oi[i], b.sampleNoTrans());
                    return oo;
                }
                else
                    return null;
            }
		};
        Listener l = listen_(out.node, new TransactionHandler<A>() {
        	public void run(Transaction trans2, A a) {
	            out.send(trans2, f.apply(a, b.sampleNoTrans()));
	        }
        });
        return out.addCleanup(l);
	}

    /**
     * Merge two streams of events of the same type.
     *
     * In the case where two event occurrences are simultaneous (i.e. both
     * within the same transaction), both will be delivered in the same
     * transaction. If the event firings are ordered for some reason, then
     * their ordering is retained. In many common cases the ordering will
     * be undefined.
     */
	public Stream<A> merge(final Stream<A> eb)
	{
	    return Stream.<A>merge(this, eb);
	}

    /**
     * Merge two streams of events of the same type.
     *
     * In the case where two event occurrences are simultaneous (i.e. both
     * within the same transaction), both will be delivered in the same
     * transaction. If the event firings are ordered for some reason, then
     * their ordering is retained. In many common cases the ordering will
     * be undefined.
     */
	private static <A> Stream<A> merge(final Stream<A> ea, final Stream<A> eb)
	{
	    final StreamSink<A> out = new StreamSink<A>() {
    		@Override
            protected Object[] sampleNow()
            {
                Object[] oa = ea.sampleNow();
                Object[] ob = eb.sampleNow();
                if (oa != null && ob != null) {
                    Object[] oo = new Object[oa.length + ob.length];
                    int j = 0;
                    for (int i = 0; i < oa.length; i++) oo[j++] = oa[i];
                    for (int i = 0; i < ob.length; i++) oo[j++] = ob[i];
                    return oo;
                }
                else
                if (oa != null)
                    return oa;
                else
                    return ob;
            }
	    };
        TransactionHandler<A> h = new TransactionHandler<A>() {
        	public void run(Transaction trans, A a) {
	            out.send(trans, a);
	        }
        };
        Listener l1 = ea.listen_(out.node, h);
        Listener l2 = eb.listen_(out.node, new TransactionHandler<A>() {
        	public void run(Transaction trans1, A a) {
                trans1.prioritized(out.node, new Handler<Transaction>() {
                    public void run(Transaction trans2) {
                        out.send(trans2, a);
                    }
                });
	        }
        });
        return out.addCleanup(l1).addCleanup(l2);
	}

	/**
	 * Push each event occurrence onto a new transaction.
	 */
	public final Stream<A> delay()
	{
	    final StreamSink<A> out = new StreamSink<A>();
	    Listener l1 = listen_(out.node, new TransactionHandler<A>() {
	        public void run(Transaction trans, final A a) {
	            trans.post(new Runnable() {
                    public void run() {
                        Transaction trans = new Transaction();
                        try {
                            out.send(trans, a);
                        } finally {
                            trans.close();
                        }
                    }
	            });
	        }
	    });
	    return out.addCleanup(l1);
	}

    /**
     * If there's more than one firing in a single transaction, combine them into
     * one using the specified combining function.
     *
     * If the event firings are ordered, then the first will appear at the left
     * input of the combining function. In most common cases it's best not to
     * make any assumptions about the ordering, and the combining function would
     * ideally be commutative.
     */
	public final Stream<A> coalesce(final Lambda2<A,A,A> f)
	{
	    return Transaction.apply(new Lambda1<Transaction, Stream<A>>() {
	    	public Stream<A> apply(Transaction trans) {
	    		return coalesce(trans, f);
	    	}
	    });
	}

	final Stream<A> coalesce(Transaction trans1, final Lambda2<A,A,A> f)
	{
	    final Stream<A> ev = this;
	    final StreamSink<A> out = new StreamSink<A>() {
    		@SuppressWarnings("unchecked")
			@Override
            protected Object[] sampleNow()
            {
                Object[] oi = ev.sampleNow();
                if (oi != null) {
					A o = (A)oi[0];
                    for (int i = 1; i < oi.length; i++)
                        o = f.apply(o, (A)oi[i]);
                    return new Object[] { o };
                }
                else
                    return null;
            }
	    };
        TransactionHandler<A> h = new CoalesceHandler<A>(f, out);
        Listener l = listen(out.node, trans1, h, false);
        return out.addCleanup(l);
    }

    /**
     * Clean up the output by discarding any firing other than the last one. 
     */
    final Stream<A> lastFiringOnly(Transaction trans)
    {
        return coalesce(trans, new Lambda2<A,A,A>() {
        	public A apply(A first, A second) { return second; }
        });
    }

    /**
     * Merge two streams of events of the same type, combining simultaneous
     * event occurrences.
     *
     * In the case where multiple event occurrences are simultaneous (i.e. all
     * within the same transaction), they are combined using the same logic as
     * 'coalesce'.
     */
    public Stream<A> merge(Stream<A> eb, Lambda2<A,A,A> f)
    {
        return merge(eb).coalesce(f);
    }

    /**
     * Only keep event occurrences for which the predicate returns true.
     */
    public final Stream<A> filter(final Lambda1<A,Boolean> f)
    {
        final Stream<A> ev = this;
        final StreamSink<A> out = new StreamSink<A>() {
    		@SuppressWarnings("unchecked")
			@Override
            protected Object[] sampleNow()
            {
                Object[] oi = ev.sampleNow();
                if (oi != null) {
                    Object[] oo = new Object[oi.length];
                    int j = 0;
                    for (int i = 0; i < oi.length; i++)
                        if (f.apply((A)oi[i]))
                            oo[j++] = oi[i];
                    if (j == 0)
                        oo = null;
                    else
                    if (j < oo.length) {
                        Object[] oo2 = new Object[j];
                        for (int i = 0; i < j; i++)
                            oo2[i] = oo[i];
                        oo = oo2;
                    }
                    return oo;
                }
                else
                    return null;
            }
        };
        Listener l = listen_(out.node, new TransactionHandler<A>() {
        	public void run(Transaction trans2, A a) {
	            if (f.apply(a)) out.send(trans2, a);
	        }
        });
        return out.addCleanup(l);
    }

    /**
     * Filter out any event occurrences whose value is a Java null pointer.
     */
    public final Stream<A> filterNotNull()
    {
        return filter(new Lambda1<A,Boolean>() {
        	public Boolean apply(A a) { return a != null; }
        });
    }

    /**
     * Filter the empty values out, and strip the Optional wrapper from the present ones.
     */
    public static final <A> Stream<A> filterOptional(final Stream<Optional<A>> ev)
    {
        final StreamSink<A> out = new StreamSink<A>() {
    		@SuppressWarnings("unchecked")
			@Override
            protected Object[] sampleNow()
            {
                Object[] oi = ev.sampleNow();
                if (oi != null) {
                    Object[] oo = new Object[oi.length];
                    int j = 0;
                    for (int i = 0; i < oi.length; i++) {
                        Optional<A> oa = (Optional<A>)oi[i];
                        if (oa.isPresent())
                            oo[j++] = oa.get();
                    }
                    if (j == 0)
                        oo = null;
                    else
                    if (j < oo.length) {
                        Object[] oo2 = new Object[j];
                        for (int i = 0; i < j; i++)
                            oo2[i] = oo[i];
                        oo = oo2;
                    }
                    return oo;
                }
                else
                    return null;
            }
        };
        Listener l = ev.listen_(out.node, new TransactionHandler<Optional<A>>() {
        	public void run(Transaction trans2, Optional<A> oa) {
	            if (oa.isPresent()) out.send(trans2, oa.get());
	        }
        });
        return out.addCleanup(l);
    }

    /**
     * Let event occurrences through only when the behavior's value is True.
     * Note that the behavior's value is as it was at the start of the transaction,
     * that is, no state changes from the current transaction are taken into account.
     */
    public final Stream<A> gate(Cell<Boolean> bPred)
    {
        return snapshot(bPred, new Lambda2<A,Boolean,A>() {
        	public A apply(A a, Boolean pred) { return pred ? a : null; }
        }).filterNotNull();
    }

    /**
     * Transform an event with a generalized state loop (a mealy machine). The function
     * is passed the input and the old state and returns the new state and output value.
     */
    public final <B,S> Stream<B> collect(final S initState, final Lambda2<A, S, Tuple2<B, S>> f)
    {
        return Transaction.<Stream<B>>run(() -> {
            final Stream<A> ea = this;
            StreamLoop<S> es = new StreamLoop<S>();
            Cell<S> s = es.hold(initState);
            Stream<Tuple2<B,S>> ebs = ea.snapshot(s, f);
            Stream<B> eb = ebs.map(new Lambda1<Tuple2<B,S>,B>() {
                public B apply(Tuple2<B,S> bs) { return bs.a; }
            });
            Stream<S> es_out = ebs.map(new Lambda1<Tuple2<B,S>,S>() {
                public S apply(Tuple2<B,S> bs) { return bs.b; }
            });
            es.loop(es_out);
            return eb;
        });
    }

    /**
     * Accumulate on input event, outputting the new state each time.
     */
    public final <S> Cell<S> accum(final S initState, final Lambda2<A, S, S> f)
    {
        return Transaction.<Cell<S>>run(() -> {
            final Stream<A> ea = this;
            StreamLoop<S> es = new StreamLoop<S>();
            Cell<S> s = es.hold(initState);
            Stream<S> es_out = ea.snapshot(s, f);
            es.loop(es_out);
            return es_out.hold(initState);
        });
    }

    /**
     * Throw away all event occurrences except for the first one.
     */
    public final Stream<A> once()
    {
        // This is a bit long-winded but it's efficient because it deregisters
        // the listener.
        final Stream<A> ev = this;
        final Listener[] la = new Listener[1];
        final StreamSink<A> out = new StreamSink<A>() {
            @Override
            protected Object[] sampleNow()
            {
                Object[] oi = ev.sampleNow();
                Object[] oo = oi;
                if (oo != null) {
                    if (oo.length > 1)
                        oo = new Object[] { oi[0] };
                    if (la[0] != null) {
                        la[0].unlisten();
                        la[0] = null;
                    }
                }
                return oo;
            }
        };
        la[0] = ev.listen_(out.node, new TransactionHandler<A>() {
        	public void run(Transaction trans, A a) {
	            out.send(trans, a);
	            if (la[0] != null) {
	                la[0].unlisten();
	                la[0] = null;
	            }
	        }
        });
        return out.addCleanup(la[0]);
    }

    Stream<A> addCleanup(Listener cleanup)
    {
        finalizers.add(cleanup);
        return this;
    }

	@Override
	protected void finalize() throws Throwable {
		for (Listener l : finalizers)
			l.unlisten();
	}
}

class CoalesceHandler<A> implements TransactionHandler<A>
{
	public CoalesceHandler(Lambda2<A,A,A> f, StreamSink<A> out)
	{
	    this.f = f;
	    this.out = out;
	}
	private Lambda2<A,A,A> f;
	private StreamSink<A> out;
    private boolean accumValid = false;
    private A accum;
    @Override
    public void run(Transaction trans1, A a) {
        if (accumValid)
            accum = f.apply(accum, a);
        else {
        	final CoalesceHandler<A> thiz = this;
            trans1.prioritized(out.node, new Handler<Transaction>() {
            	public void run(Transaction trans2) {
                    out.send(trans2, thiz.accum);
                    thiz.accumValid = false;
                    thiz.accum = null;
                }
            });
            accum = a;
            accumValid = true;
        }
    }
}

