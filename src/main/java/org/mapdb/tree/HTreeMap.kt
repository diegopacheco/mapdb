package org.mapdb.tree

import com.google.common.collect.Iterators
import org.eclipse.collections.api.map.primitive.MutableLongLongMap
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet
import org.mapdb.*
import org.mapdb.queue.*
import org.mapdb.hasher.Hasher
import org.mapdb.serializer.Serializer
import org.mapdb.serializer.Serializers
import org.mapdb.store.*
import org.mapdb.util.*
import java.io.Closeable
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.*
import java.util.function.BiConsumer

/**
 * Concurrent HashMap which uses IndexTree for hash table
 */
class HTreeMap<K,V>(
        override val keySerializer: Serializer<K>,
        override val valueSerializer: Serializer<V>,
        val valueInline:Boolean,
        val concShift: Int,
        val dirShift: Int,
        val levels: Int,
        val stores: Array<Store>,
        val indexTrees: Array<MutableLongLongMap>,
        private val hashSeed:Int,
        counterRecids:LongArray?,
        val expireCreateTTL:Long,
        val expireUpdateTTL:Long,
        val expireGetTTL:Long,
        val expireMaxSize:Long,
        val expireStoreSize:Long,
        val expireCreateQueues:Array<QueueLong>?,
        val expireUpdateQueues:Array<QueueLong>?,
        val expireGetQueues:Array<QueueLong>?,
        val expireExecutor: ScheduledExecutorService?,
        val expireExecutorPeriod:Long,
        val expireCompactThreshold:Double?,
        override val isThreadSafe:Boolean,
        val valueLoader:((key:K)->V?)?,
        private val modificationListeners: Array<MapModificationListener<K,V>>?,
        private val closeable:Closeable?,
        val hasValues:Boolean = true

        //TODO queue is probably sequentially unsafe

) : DBConcurrentMap<K,V>{


    companion object{
        /** constructor with default values */
        fun <K,V> make(
                @Suppress("UNCHECKED_CAST")
                keySerializer: Serializer<K> = Serializers.ELSA as Serializer<K>,
                @Suppress("UNCHECKED_CAST")
                valueSerializer: Serializer<V> = Serializers.ELSA as Serializer<V>,
                valueInline:Boolean = false,
                concShift: Int = CC.HTREEMAP_CONC_SHIFT,
                dirShift: Int = CC.HTREEMAP_DIR_SHIFT,
                levels:Int = CC.HTREEMAP_LEVELS,
                stores:Array<Store> = Array(1.shl(concShift), {StoreTrivial()}),
                indexTrees: Array<MutableLongLongMap> = Array(1.shl(concShift), { i->IndexTreeLongLongMap.make(stores[i], levels=levels, dirShift = dirShift)}),
                hashSeed:Int = SecureRandom().nextInt(),
                counterRecids:LongArray? = null,
                expireCreateTTL:Long = 0L,
                expireUpdateTTL:Long = 0L,
                expireGetTTL:Long = 0L,
                expireMaxSize:Long = 0L,
                expireStoreSize:Long = 0L,
                expireCreateQueues:Array<QueueLong>? = if(expireCreateTTL<=0L) null else Array(stores.size, { i-> QueueLong.make(store = stores[i])}),
                expireUpdateQueues:Array<QueueLong>? = if(expireUpdateTTL<=0L) null else Array(stores.size, { i-> QueueLong.make(store = stores[i])}),
                expireGetQueues:Array<QueueLong>? = if(expireGetTTL<=0L) null else Array(stores.size, { i-> QueueLong.make(store = stores[i])}),
                expireExecutor:ScheduledExecutorService? = null,
                expireExecutorPeriod:Long = 0,
                expireCompactThreshold:Double? = null,
                isThreadSafe:Boolean = true,
                valueLoader:((key:K)->V)? = null,
                modificationListeners: Array<MapModificationListener<K,V>>? = null,
                closeable: Closeable? = null
        ) = HTreeMap(
                keySerializer = keySerializer,
                valueSerializer = valueSerializer,
                valueInline = valueInline,
                concShift = concShift,
                dirShift = dirShift,
                levels = levels,
                stores = stores,
                indexTrees = indexTrees,
                hashSeed = hashSeed,
                counterRecids = counterRecids,
                expireCreateTTL = expireCreateTTL,
                expireUpdateTTL = expireUpdateTTL,
                expireGetTTL = expireGetTTL,
                expireMaxSize = expireMaxSize,
                expireStoreSize = expireStoreSize,
                expireCreateQueues = expireCreateQueues,
                expireUpdateQueues = expireUpdateQueues,
                expireGetQueues = expireGetQueues,
                expireExecutor = expireExecutor,
                expireExecutorPeriod = expireExecutorPeriod,
                expireCompactThreshold = expireCompactThreshold,
                isThreadSafe = isThreadSafe,
                valueLoader = valueLoader,
                modificationListeners = modificationListeners,
                closeable = closeable
            )

        @JvmField protected val QUEUE_CREATE=1L
        @JvmField protected val QUEUE_UPDATE=2L
        @JvmField protected val QUEUE_GET=3L
    }


    private val keyHasher: Hasher<K> = keySerializer.defaultHasher()
    private val valueHasher: Hasher<V> = valueSerializer.defaultHasher()

    private val segmentCount = 1.shl(concShift)

    private val storesUniqueCount = Utils.identityCount(stores)

    protected val locks = newReadWriteSegmentedLock(threadSafe = isThreadSafe, segmentCount=segmentCount)

    /** true if Eviction is executed inside user thread, as part of get/put etc operations */
    protected val isForegroundEviction:Boolean = expireExecutor==null &&
            (expireCreateQueues!=null || expireUpdateQueues!=null || expireGetQueues!=null)

    protected val modificationListenersEmpty = modificationListeners==null || modificationListeners.isEmpty()

    protected val counters : Array<Atomic.Long>? =
            if(counterRecids == null) null
            else counterRecids.mapIndexed{i:Int, recid:Long -> Atomic.Long(stores[i], recid, true) }.toTypedArray()

    init{
        if(segmentCount!=stores.size)
            throw IllegalArgumentException("stores size wrong")
        if(segmentCount!=indexTrees.size)
            throw IllegalArgumentException("indexTrees size wrong")
        if(expireCreateQueues!=null && segmentCount!=expireCreateQueues.size)
            throw IllegalArgumentException("expireCreateQueues size wrong")
        if(expireUpdateQueues!=null && segmentCount!=expireUpdateQueues.size)
            throw IllegalArgumentException("expireUpdateQueues size wrong")
        if(expireGetQueues!=null && segmentCount!=expireGetQueues.size)
            throw IllegalArgumentException("expireGetQueues size wrong")
        if(BTreeMap.NO_VAL_SERIALIZER==valueSerializer && hasValues)
            throw IllegalArgumentException("wrong value serializer")
        if(BTreeMap.NO_VAL_SERIALIZER!=valueSerializer && !hasValues)
            throw IllegalArgumentException("wrong value serializer")
        if(!hasValues && !valueInline){
            throw IllegalArgumentException("value inline must be enabled for KeySet")
        }

        //schedule background expiration if needed
        if(expireExecutor!=null && (expireCreateQueues!=null || expireUpdateQueues!=null || expireGetQueues!=null)){
            for(segment in 0 until segmentCount){
                expireExecutor.scheduleAtFixedRate(Utils.logExceptions({
                    locks.lockWrite(segment){
                        expireEvictSegment(segment)
                    }
                }),
                (expireExecutorPeriod * Math.random()).toLong(), // put random delay, so eviction are not executed all at once
                expireExecutorPeriod, TimeUnit.MILLISECONDS)
            }
        }

        //check if 32bit hash covers all indexes. In future we will upgrade to 64bit hash and this can be removed
        if(segmentCount*Math.pow(1.shl(dirShift).toDouble(),levels.toDouble()) > 2L*Integer.MAX_VALUE+1000){
            Utils.LOG.warning { "Wrong layout, segment+index is more than 32bits, performance degradation" }
        }
    }


    private fun leafValueInlineSerializer() = object: Serializer<Array<Any>> {
        override fun serialize(out: DataOutput2, value: kotlin.Array<Any>) {
            out.packInt(value.size)
            for(i in 0 until value.size step 3) {
                @Suppress("UNCHECKED_CAST")
                keySerializer.serialize(out, value[i+0] as K)
                @Suppress("UNCHECKED_CAST")
                valueSerializer.serialize(out, value[i+1] as V)
                out.packLong(value[i+2] as Long)
            }
        }

        override fun deserialize(input: DataInput2, available: Int): kotlin.Array<Any> {
            val ret:Array<Any?> = arrayOfNulls(input.unpackInt())
            var i = 0;
            while(i<ret.size) {
                ret[i++] = keySerializer.deserialize(input, -1)
                ret[i++] = valueSerializer.deserialize(input, -1)
                ret[i++] = input.unpackLong()
            }
            @Suppress("UNCHECKED_CAST")
            return ret as Array<Any>
        }

        override fun isTrusted(): Boolean {
            return keySerializer.isTrusted && valueSerializer.isTrusted
        }
    }

    private fun leafKeySetSerializer() = object: Serializer<Array<Any>> {
        override fun serialize(out: DataOutput2, value: kotlin.Array<Any>) {
            out.packInt(value.size)
            for(i in 0 until value.size step 3) {
                @Suppress("UNCHECKED_CAST")
                keySerializer.serialize(out, value[i+0] as K)
                out.packLong(value[i+2] as Long)
            }
        }

        override fun deserialize(input: DataInput2, available: Int): kotlin.Array<Any> {
            val ret:Array<Any?> = arrayOfNulls(input.unpackInt())
            var i = 0;
            while(i<ret.size) {
                ret[i++] = keySerializer.deserialize(input, -1)
                ret[i++] = true
                ret[i++] = input.unpackLong()
            }
            @Suppress("UNCHECKED_CAST")
            return ret as Array<Any>;
        }

        override fun isTrusted(): Boolean {
            return keySerializer.isTrusted && valueSerializer.isTrusted
        }
    }



    private fun leafValueExternalSerializer() = object: Serializer<Array<Any>> {
        override fun serialize(out: DataOutput2, value: Array<Any>) {
            out.packInt(value.size)
            for(i in 0 until value.size step 3) {
                @Suppress("UNCHECKED_CAST")
                keySerializer.serialize(out, value[i+0] as K)
                out.packLong(value[i+1] as Long)
                out.packLong(value[i+2] as Long)
            }
        }

        override fun deserialize(input: DataInput2, available: Int): Array<Any> {
            val ret:Array<Any?> = arrayOfNulls(input.unpackInt())
            var i = 0;
            while(i<ret.size) {
                ret[i++] = keySerializer.deserialize(input, -1)
                ret[i++] = input.unpackLong()
                ret[i++] = input.unpackLong() //expiration timestamp
            }
            @Suppress("UNCHECKED_CAST")
            return ret as Array<Any>;
        }

        override fun isTrusted(): Boolean {
            return keySerializer.isTrusted
        }
    }



    //TODO Expiration QueueID is part of leaf, remove it if expiration is disabled!
    protected val leafSerializer: Serializer<Array<Any>> =
            if(!hasValues)
                leafKeySetSerializer()
            else if(valueInline)
                leafValueInlineSerializer()
            else
                leafValueExternalSerializer()


    private val indexMask = (IndexTreeListJava.full.shl(levels*dirShift)).inv();
    private val concMask = IndexTreeListJava.full.shl(concShift).inv().toInt();

    /**
     * Variable used to check for first put() call, it verifies that hashCode of inserted key is stable.
     * Note: this variable is not thread safe, but that is fine, worst case scenario is check will be performed multiple times.
     *
     * This step is ignored for StoreOnHeap, because serialization is not involved here, and it might failnew
     */
    private var checkHashAfterSerialization = stores.find { it is StoreOnHeap } == null


    protected fun hash(key:K):Int{
        //FIXME seed is zero?
        return keyHasher.hashCode(key, 0)
    }
    protected fun hashToIndex(hash:Int) = DataIO.intToLong(hash) and indexMask
    protected fun hashToSegment(hash:Int) = hash.ushr(levels*dirShift) and concMask



    private inline fun <E> segmentRead(segment:Int, body:()->E):E{
        // if expireGetQueue is modified on get, we need write lock
        val readLock = expireGetQueues==null && valueLoader ==null

        if(readLock)
            locks?.readLock(segment)
        else
            locks?.writeLock(segment)
        try {
            return body()
        }finally{
            if(readLock)
                locks?.readUnlock(segment)
            else
                locks?.writeUnlock(segment)
        }
    }

    override fun put(key: K, value: V): V? {
        return put2(key, value, false)
    }

    override fun putOnly(key: K, value: V){
        put2(key, value, true)
    }

    protected fun put2(key: K?, value: V?, noValueExpand:Boolean): V? {
        if (key == null || value == null)
            throw NullPointerException()

        val hash = hash(key)
        if(checkHashAfterSerialization){
            checkHashAfterSerialization = false;
            //check if hash is the same after cloning
            val key2 = Utils.clone(key, keySerializer)
            if(hash(key2)!=hash){
                throw IllegalArgumentException("Key.hashCode() changed after serialization, make sure to use correct Key Serializer")
            }
        }

        val segment = hashToSegment(hash)
        locks.lockWrite(segment) {->
            if(isForegroundEviction)
                expireEvictSegment(segment)

            return putProtected(hash, key, value, false, noValueExpand)
        }
    }

    protected fun putProtected(hash:Int, key:K, value:V, triggered:Boolean, noValueExpand:Boolean):V?{
        val segment = hashToSegment(hash)
        if(CC.PARANOID)
            locks?.checkWriteLocked(segment)
        if(CC.PARANOID && hash!= hash(key))
            throw AssertionError()


        val index = hashToIndex(hash)
        val store = stores[segment]
        val indexTree = indexTrees[segment]

        val leafRecid = indexTree.get(index)

        if (leafRecid == 0L) {
            //not found, insert new record
            val wrappedValue = valueWrap(segment, value)

            val leafRecid2 =
                    if (expireCreateQueues == null) {
                        // no expiration, so just insert
                        val leaf = arrayOf(key as Any, wrappedValue, 0L)
                        store.put(leaf, leafSerializer)
                    } else {
                        // expiration is involved, and there is cyclic dependency between expireRecid and leafRecid
                        // must use preallocation and update to solve it
                        val leafRecid2 = store.preallocate()
                        val expireRecid = expireCreateQueues[segment].put(
                                if(expireCreateTTL==-1L) 0L else System.currentTimeMillis()+expireCreateTTL,
                                leafRecid2)
                        val leaf = arrayOf(key as Any, wrappedValue, expireId(expireRecid, QUEUE_CREATE))
                        store.update(leafRecid2, leaf, leafSerializer)
                        leafRecid2
                    }
            counters?.get(segment)?.increment()
            indexTree.put(index, leafRecid2)

            listenerNotify(key, null, value, triggered)
            return null
        }

        var leaf = leafGet(store, leafRecid)

        //check existing keys in leaf
        for (i in 0 until leaf.size step 3) {
            @Suppress("UNCHECKED_CAST")
            val oldKey = leaf[i] as K

            if (keyHasher.equals(oldKey, key)) {
                //match found, update existing value
                val oldVal =
                        if(noValueExpand && modificationListenersEmpty) null
                        else valueUnwrap(segment, leaf[i + 1])

                if (expireUpdateQueues != null) {
                    //update expiration stuff
                    if (leaf[i + 2] != 0L) {
                        //it exist in old queue
                        val expireId = leaf[i + 2] as Long
                        val oldQueue = expireQueueFor(segment,expireId)
                        val nodeRecid = expireNodeRecidFor(expireId)
                        if (oldQueue === expireUpdateQueues[segment]) {
                            //just bump
                            oldQueue.bump(nodeRecid, if(expireUpdateTTL==-1L) 0L else System.currentTimeMillis()+expireUpdateTTL)
                        } else {
                            //remove from old queue
                            val oldNode = oldQueue.remove(nodeRecid, removeNode = false)

                            //and put into new queue, reuse recid
                            expireUpdateQueues[segment].put(
                                    timestamp = if(expireUpdateTTL==-1L) 0L else System.currentTimeMillis()+expireUpdateTTL,
                                    value=oldNode.value, nodeRecid = nodeRecid )

                            leaf = leaf.copyOf()
                            leaf[i + 2] = expireId(nodeRecid, QUEUE_UPDATE)
                            store.update(leafRecid, leaf, leafSerializer)
                        }
                    } else {
                        //does not exist in old queue, insert new
                        val expireRecid = expireUpdateQueues[segment].put(
                                if(expireUpdateTTL==-1L) 0L else System.currentTimeMillis()+expireUpdateTTL,
                                leafRecid);
                        leaf = leaf.copyOf()
                        leaf[i + 2] = expireId(expireRecid, QUEUE_UPDATE)
                        store.update(leafRecid, leaf, leafSerializer)
                    }
                }

                if(!valueInline) {
                    //update external record
                    store.update(leaf[i+1] as Long, value, valueSerializer)
                }else{
                    //stored inside leaf, so clone leaf, swap and update
                    leaf = leaf.copyOf();
                    leaf[i+1] = value as Any;
                    store.update(leafRecid, leaf, leafSerializer)
                }
                listenerNotify(key, oldVal, value, triggered)
                return oldVal
            }
        }

        //no key in leaf matches ours, so insert new key and update leaf
        val wrappedValue = valueWrap(segment, value)

        leaf = Arrays.copyOf(leaf, leaf.size + 3)
        leaf[leaf.size-3] = key as Any
        leaf[leaf.size-2] = wrappedValue
        leaf[leaf.size-1] = 0L

        if (expireCreateQueues != null) {
            val expireRecid = expireCreateQueues[segment].put(
                    if(expireCreateTTL==-1L) 0L else System.currentTimeMillis()+expireCreateTTL,
                    leafRecid);
            leaf[leaf.size-1] = expireId(expireRecid, QUEUE_CREATE)
        }

        store.update(leafRecid, leaf, leafSerializer)
        counters?.get(segment)?.increment()
        listenerNotify(key, null, value, triggered)
        return null

    }

    override fun putAll(from: Map<out K, V>) {
        for(e in from.entries){
            put(e.key, e.value)
        }
    }

    override fun remove(key: K): V? {
        if(key == null)
            throw NullPointerException()
        val hash = hash(key)
        val segment = hashToSegment(hash)
        locks.lockWrite(segment) {->
            if(isForegroundEviction)
                expireEvictSegment(segment)

            return removeProtected(hash, key, false, false) as V?
        }
    }

    protected fun removeProtected(hash:Int, key: K, evicted:Boolean, retTrue:Boolean): Any? {
        val segment = hashToSegment(hash)
        if(CC.PARANOID)
            locks?.checkWriteLocked(segment)
        if(CC.PARANOID && hash!= hash(key))
            throw AssertionError()

        val index = hashToIndex(hash)
        val store = stores[segment]
        val indexTree = indexTrees[segment]

        val leafRecid = indexTree.get(index)
        if (leafRecid == 0L)
            return null

        val leaf = leafGet(store, leafRecid)

        //check existing keys in leaf
        for (i in 0 until leaf.size step 3) {
            @Suppress("UNCHECKED_CAST")
            val oldKey = leaf[i] as K

            if (keyHasher.equals(oldKey, key)) {
                if (!evicted && leaf[i + 2] != 0L) {
                    //if entry is evicted, queue will be updated at other place, so no need to remove queue in that case
                    val queue = expireQueueFor(segment, leaf[i + 2] as Long)
                    queue.remove(expireNodeRecidFor(leaf[i + 2] as Long), removeNode = true)
                }

                val oldVal =
                        if(retTrue && modificationListenersEmpty) true
                        else valueUnwrap(segment, leaf[i + 1])

                //remove from leaf and from store
                if (leaf.size == 3) {
                    //single entry, collapse leaf
                    indexTree.removeKey(index)
                    store.delete(leafRecid, leafSerializer)
                } else {
                    //more entries, update leaf
                    store.update(leafRecid,
                            DataIO.arrayDelete(leaf, i + 3, 3),
                            leafSerializer)
                }

                if(!valueInline)
                    store.delete(leaf[i+1] as Long, valueSerializer)
                counters?.get(segment)?.decrement()
                if(!modificationListenersEmpty)
                    listenerNotify(key, oldVal as V?, null, evicted)
                return oldVal
            }
        }

        //nothing to delete
        return null;
    }

    override fun clear() {
        clear(notifyListeners=1)
    }

    @Deprecated("use clearWithoutNotifaction() method")
    fun clear2(notifyListeners:Boolean=true) {
        clear(if(notifyListeners)1 else 0)
    }

    /** Removes all entries from this Map, but does not notify modification listeners */
    //TODO move this to DBConcurrentMap interface, add to BTreeMap
    fun clearWithoutNotification(){
        clear(notifyListeners = 0)
    }

    /** Removes all entries from this Map, and notifies listeners as if content has expired.
     * This will cause expired content to overflow to secondary collections etc
     */
    fun clearWithExpire(){
        clear(notifyListeners = 2)
    }

    protected fun clear(notifyListeners:Int=1) {
        //TODO not sequentially safe
        val notify = notifyListeners>0 &&  modificationListeners!=null && modificationListeners.isEmpty().not()
        val triggerExpiration = notifyListeners==2
        for(segment in 0 until segmentCount) {
            locks.lockWrite(segment) {
                val indexTree = indexTrees[segment]
                val store = stores[segment]
                indexTree.forEachKeyValue { _, leafRecid ->
                    val leaf = leafGet(store, leafRecid)

                    store.delete(leafRecid, leafSerializer);
                    for (i in 0 until leaf.size step 3) {
                        val key = leaf[i]
                        val wrappedValue = leaf[i + 1]
                        if (notify)
                            listenerNotify(
                                    @Suppress("UNCHECKED_CAST") (key as K),
                                    valueUnwrap(segment, wrappedValue),
                                    null,
                                    triggerExpiration)
                        if (!valueInline)
                            store.delete(wrappedValue as Long, valueSerializer)
                    }
                }
                expireCreateQueues?.get(segment)?.clear()
                expireUpdateQueues?.get(segment)?.clear()
                expireGetQueues?.get(segment)?.clear()
                indexTree.clear()

                counters?.forEach { it.set(0)}
            }
        }
    }


    override fun containsKey(key: K): Boolean {
        if (key == null)
            throw NullPointerException()

        val hash = hash(key)

        segmentRead(hashToSegment(hash)) { ->
            return null!=getprotected(hash, key, updateQueue = false)
        }
    }

    override fun containsValue(value: V): Boolean {
        if(value==null)
            throw NullPointerException();
        return values.contains(value)
    }

    override fun get(key: K): V? {
        if (key == null)
            throw NullPointerException()

        val hash = hash(key)
        val segment = hashToSegment(hash)
        segmentRead(segment) { ->
            if(isForegroundEviction && expireGetQueues!=null)
                expireEvictSegment(segment)
            var ret =  getprotected(hash, key, updateQueue = true)
            if(ret==null && valueLoader !=null){
                ret = valueLoader!!(key)
                if(ret!=null)
                    putProtected(hash, key, ret, true, true)
            }
            return ret
        }
    }

    protected fun getprotected(hash:Int, key:K, updateQueue:Boolean):V?{
        val segment = hashToSegment(hash)
        if(CC.PARANOID) {
            if(updateQueue && expireGetQueues!=null)
                locks?.checkWriteLocked(segment)
            else
                locks?.checkReadLocked(segment)
        }
        if(CC.PARANOID && hash!= hash(key))
            throw AssertionError()


        val index = hashToIndex(hash)
        val store = stores[segment]
        val indexTree = indexTrees[segment]

        val leafRecid = indexTree.get(index)
        if (leafRecid == 0L)
            return null

        var leaf = leafGet(store, leafRecid)

        for (i in 0 until leaf.size step 3) {
            @Suppress("UNCHECKED_CAST")
            val oldKey = leaf[i] as K

            if (keyHasher.equals(oldKey, key)) {

                if (expireGetQueues != null) {
                    leaf = getprotectedQueues(expireGetQueues, i, leaf, leafRecid, segment, store)
                }

                return valueUnwrap(segment, leaf[i + 1])
            }
        }
        //nothing found
        return null
    }

    private fun getprotectedQueues(expireGetQueues: Array<QueueLong>, i: Int, leaf: Array<Any>, leafRecid: Long, segment: Int, store: Store): Array<Any> {
        if(CC.PARANOID)
            locks?.checkWriteLocked(segment)

        //update expiration stuff
        var leaf1 = leaf
        if (leaf1[i + 2] != 0L) {
            //it exist in old queue
            val expireId = leaf1[i + 2] as Long
            val oldQueue = expireQueueFor(segment, expireId)
            val nodeRecid = expireNodeRecidFor(expireId)
            if (oldQueue === expireGetQueues[segment]) {
                //just bump
                oldQueue.bump(nodeRecid, if(expireGetTTL==-1L) 0L else System.currentTimeMillis()+expireGetTTL)
            } else {
                //remove from old queue
                val oldNode = oldQueue.remove(nodeRecid, removeNode = false)
                //and put into new queue, reuse recid
                expireGetQueues[segment].put(
                        timestamp = if(expireGetTTL==-1L) 0L else System.currentTimeMillis()+expireGetTTL,
                        value = oldNode.value, nodeRecid = nodeRecid)
                //update queue id
                leaf1 = leaf1.copyOf()
                leaf1[i + 2] = expireId(nodeRecid, QUEUE_GET)
                store.update(leafRecid, leaf1, leafSerializer)
            }
        } else {
            //does not exist in old queue, insert new
            val expireRecid = expireGetQueues[segment].put(
                    if(expireGetTTL==-1L) 0L else System.currentTimeMillis()+expireGetTTL,
                    leafRecid);
            leaf1 = leaf1.copyOf()
            leaf1[i + 2] = expireId(expireRecid, QUEUE_GET)
            store.update(leafRecid, leaf1, leafSerializer)

        }
        return leaf1
    }

    override fun isEmpty(): Boolean {
        for(segment in 0 until segmentCount) {
            locks.lockRead(segment) {
                if (!indexTrees[segment].isEmpty)
                    return false
            }
        }
        return true;
    }

    override val size: Int
        get() = Utils.roundDownToIntMAXVAL(sizeLong())

    override fun sizeLong():Long{
        var ret = 0L
        for(segment in 0 until segmentCount) {

            locks.lockRead(segment){
                if(counters!=null){
                    ret += counters[segment].get()
                }else {
                    indexTrees[segment].forEachKeyValue { _, leafRecid ->
                        val leaf = leafGet(stores[segment], leafRecid)
                        ret += leaf.size / 3
                    }
                }
            }
        }

        return ret;
    }

    override fun putIfAbsent(key: K, value: V): V? {
        if(key == null || value==null)
            throw NullPointerException()

        val hash = hash(key)
        val segment = hashToSegment(hash)
        locks.lockWrite( segment) {
            if(isForegroundEviction)
                expireEvictSegment(segment)

            return getprotected(hash,key, updateQueue = false) ?:
                    putProtected(hash, key, value,false, false)
        }
    }


    override fun putIfAbsentBoolean(key: K, value: V): Boolean {
        if(key == null || value==null)
            throw NullPointerException()

        val hash = hash(key)
        val segment = hashToSegment(hash)
        locks.lockWrite( segment) {
            if(isForegroundEviction)
                expireEvictSegment(segment)

            if (getprotected(hash, key, updateQueue = false) != null)
                return false
            putProtected(hash, key, value, false, true)
            return true;
        }
    }

    override fun remove(key: K, value: V): Boolean {
        if(key == null || value==null)
            throw NullPointerException()

        val hash = hash(key)
        val segment = hashToSegment(hash)
        locks.lockWrite( segment) {
            if(isForegroundEviction)
                expireEvictSegment(segment)

            val oldValue = getprotected(hash, key, updateQueue = false)
            if (oldValue != null && valueHasher.equals(oldValue, value)) {
                removeProtected(hash, key, evicted = false, retTrue = false)
                return true
            } else {
                return false
            }
        }
    }

    override fun removeBoolean(key: K): Boolean {
        if(key == null)
            throw NullPointerException()
        val hash = hash(key)
        val segment = hashToSegment(hash)
        locks.lockWrite( segment) {->
            if(isForegroundEviction)
                expireEvictSegment(segment)

            return removeProtected(hash, key, false, retTrue = true) !=null
        }
    }

    override fun replace(key: K, oldValue: V, newValue: V): Boolean {
        if(key == null || oldValue==null || newValue==null)
            throw NullPointerException()
        val hash = hash(key)
        val segment = hashToSegment(hash)
        locks.lockWrite( segment) {
            if(isForegroundEviction)
                expireEvictSegment(segment)

            val valueIn = getprotected(hash, key, updateQueue = false);
            if (valueIn != null && valueHasher.equals(valueIn, oldValue)) {
                putProtected(hash, key, newValue, false, true);
                return true;
            } else {
                return false;
            }
        }
    }

    override fun replace(key: K, value: V): V? {
        if(key == null || value==null)
            throw NullPointerException()

        val hash = hash(key)
        val segment = hashToSegment(hash)
        locks.lockWrite( segment) {
            if(isForegroundEviction)
                expireEvictSegment(segment)

            if (getprotected(hash, key,updateQueue = false)!=null) {
                return putProtected(hash, key, value, false, false);
            } else {
                return null;
            }
        }
    }



    protected fun expireNodeRecidFor(expireId: Long): Long {
        return expireId.ushr(2)
    }

    protected fun expireQueueFor(segment:Int, expireId: Long): QueueLong {
        return when(expireId and 3){
            1L -> expireCreateQueues?.get(segment)
            2L -> expireUpdateQueues?.get(segment)
            3L -> expireGetQueues?.get(segment)
            else -> throw DBException.DataCorruption("wrong queue")
        } ?: throw IllegalStateException("no queue is set")

    }

    protected fun expireId(nodeRecid: Long, queue:Long):Long{
        if(CC.PARANOID && queue !in 1L..3L)
            throw AssertionError("Wrong queue id: "+queue)
        if(CC.PARANOID && nodeRecid==0L)
            throw AssertionError("zero nodeRecid")
        return nodeRecid.shl(2) + queue
    }

    /** releases old stuff from queue */
    fun expireEvict(){
        for(segment in 0 until segmentCount) {
            locks.lockWrite( segment) {
                expireEvictSegment(segment)
            }
        }
    }

    protected fun expireEvictSegment(segment:Int){
        if(CC.PARANOID)
            locks?.checkWriteLocked(segment)

        val currTimestamp = System.currentTimeMillis()
        var numberToTake:Long =
                if(expireMaxSize==0L) 0L
                else{
                    val segmentSize = counters!![segment].get()
                    Math.max(0L, (segmentSize*segmentCount-expireMaxSize)/segmentCount)
                }
        for (q in arrayOf(expireGetQueues?.get(segment), expireUpdateQueues?.get(segment), expireCreateQueues?.get(segment))) {
            q?.takeUntil(QueueLongTakeUntil { nodeRecid, node ->
                var purged = false;

                //expiration based on maximal Map size
                if (numberToTake > 0) {
                    numberToTake--
                    purged = true
                }

                //expiration based on TTL
                if (!purged && node.timestamp != 0L && node.timestamp < currTimestamp) {
                    purged = true
                }

                //expiration based on maximal store size
                if (!purged && expireStoreSize != 0L) {
                    val store = stores[segment] as StoreDirect
                    purged = store.fileTail - store.getFreeSize() > expireStoreSize
                }

                if (purged) {
                    //remove entry from Map
                    expireEvictEntry(segment = segment, leafRecid = node.value, nodeRecid = nodeRecid)
                }
                purged
            })
        }

        //trigger compaction?
        if(expireCompactThreshold!=null){
            val store = stores[segment]
            if(store is StoreDirect){
                val totalSize = store.getTotalSize().toDouble()
                if(store.getFreeSize().toDouble()/totalSize > expireCompactThreshold) {
                    store.compact()
                }
            }
        }
    }

    protected fun expireEvictEntry(segment:Int, leafRecid:Long, nodeRecid:Long){
        if(CC.PARANOID)
            locks?.checkWriteLocked(segment)

        val leaf = stores[segment].get(leafRecid, leafSerializer)
                ?: throw DBException.DataCorruption("linked leaf not found")

        for(leafIndex in 0 until leaf.size step 3){
            if(nodeRecid != expireNodeRecidFor(leaf[leafIndex+2] as Long))
                continue
            //remove from this leaf
            @Suppress("UNCHECKED_CAST")
            val key = leaf[leafIndex] as K
            val hash = hash(key);
            if(CC.PARANOID && segment!=hashToSegment(hash))
                throw AssertionError()
            val old = removeProtected(hash = hash, key = key, evicted = true, retTrue = false)
            //TODO PERF if leaf has two or more items, delete directly from leaf
            if(CC.PARANOID && old==null)
                throw AssertionError()
            return;
        }

        throw DBException.DataCorruption("nodeRecid not found in this leaf")
    }


    //TODO retailAll etc should use serializers for comparasions, remove AbstractSet and AbstractCollection completely
    //TODO PERF replace iterator with forEach, much faster indexTree traversal
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>> = object : AbstractSet<MutableMap.MutableEntry<K, V>>() {

        override fun add(element: MutableMap.MutableEntry<K, V>): Boolean {
            return null!=this@HTreeMap.put(element.key, element.value)
        }


        override fun clear() {
            this@HTreeMap.clear()
        }

        override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> {
            val iters = (0 until segmentCount).map{segment->
                htreeSegmentIterator(segment) { key, wrappedValue ->
                    @Suppress("UNCHECKED_CAST")
                    htreeEntry(
                            key as K,
                            valueUnwrap(segment, wrappedValue))
                }
            }
            return Iterators.concat(iters.iterator())
        }

        override fun remove(element: MutableMap.MutableEntry<K, V>): Boolean {
            return this@HTreeMap.remove(element.key, element.value)
        }


        override fun contains(element: MutableMap.MutableEntry<K, V>): Boolean {
            val v = this@HTreeMap.get(element.key)
                    ?: return false
            val value = element.value
                    ?: return false
            return valueHasher.equals(value,v)
        }

        override fun isEmpty(): Boolean {
            return this@HTreeMap.isEmpty()
        }

        override val size: Int
            get() = this@HTreeMap.size

    }

    class KeySet<K>(val map:HTreeMap<K,Any?>): AbstractSet<K>(), DBSet<K>{

        override fun verify() {
            map.verify()
        }

        override fun close() {
            map.close()
        }

        override val isThreadSafe: Boolean
            get() = map.isThreadSafe

        override fun spliterator(): Spliterator<K> {
            return super<AbstractSet>.spliterator()
        }

        override fun iterator(): MutableIterator<K?> {
            val iters = (0 until map.segmentCount).map{segment->
                map.htreeSegmentIterator(segment) { key, _ ->
                    @Suppress("UNCHECKED_CAST")
                    key as K
                }
            }
            return Iterators.concat(iters.iterator())
        }

        override val size: Int
        get() = map.size


        override fun add(element: K): Boolean {
            if(map.hasValues)
                throw UnsupportedOperationException("Can not add without val")
            return map.put(element, true as Any?)==null
        }

        override fun clear() {
            map.clear()
        }

        override fun isEmpty(): Boolean {
            return map.isEmpty()
        }

        override fun remove(key: K): Boolean {
            return map.removeBoolean(key)
        }
    }

    override val keys: KeySet<K> = KeySet(@Suppress("UNCHECKED_CAST") (this as HTreeMap<K,Any?>))

    override val values: MutableCollection<V> = object : AbstractCollection<V>(){

        override fun clear() {
            this@HTreeMap.clear()
        }

        override fun isEmpty(): Boolean {
            return this@HTreeMap.isEmpty()
        }

        override val size: Int
            get() = this@HTreeMap.size

        override fun iterator(): MutableIterator<V> {
            val iters = (0 until segmentCount).map{segment->
                htreeSegmentIterator(segment) { _, valueWrapped ->
                    valueUnwrap(segment, valueWrapped)
                }
            }
            return Iterators.concat(iters.iterator())
        }
    }

    /** iterator over data in single method */
    protected fun <E> htreeSegmentIterator(segment:Int, loadNext:(wrappedKey:Any, wrappedValue:Any)->E ):MutableIterator<E>{
        return object : MutableIterator<E>{

            /** marker to indicate that leafArray has expired and nextLeaf needs to be loaded*/
            val loadNextLeafPreinit:Array<Any?> = arrayOf(null)

            val store = stores[segment]

            val leafRecidIter = indexTrees[segment].values().longIterator()
            var leafPos = 0

            /** array of key-values in current leaf node. Null indicates end of iterator, `=== loadNextLead` loads next leaf */
            var leafArray:Array<Any?>? = loadNextLeafPreinit

            /** usef for remove() */
            var lastKey:K? = null

            private fun checkNextLeaf(){
                if(leafPos == 0 && leafArray === loadNextLeafPreinit){
                    leafArray = moveToNextLeaf()
                }
            }

            private fun moveToNextLeaf(): Array<Any?>? {
                locks.lockRead(segment) {
                    if (!leafRecidIter.hasNext()) {
                        return null
                    }
                    val leafRecid = leafRecidIter.next()
                    val leaf = leafGet(store, leafRecid)
                    val ret = Array<Any?>(leaf.size, { null })
                    for (i in 0 until ret.size step 3) {
                        ret[i] = loadNext(leaf[i], leaf[i + 1])
                        @Suppress("UNCHECKED_CAST")
                        ret[i + 1] = leaf[i] as K
                    }
                    return ret
                }
            }


            override fun hasNext(): Boolean {
                checkNextLeaf()
                return leafArray!=null
            }

            override fun next(): E {
                checkNextLeaf()
                val leafArray = leafArray
                        ?: throw NoSuchElementException();
                val ret = leafArray[leafPos++]
                @Suppress("UNCHECKED_CAST")
                lastKey = leafArray[leafPos++] as K?
                val expireRecid = leafArray[leafPos++]

                if(leafPos==leafArray.size){
                    this.leafArray = loadNextLeafPreinit
                    this.leafPos = 0;
                }
                @Suppress("UNCHECKED_CAST")
                return ret as E
            }

            override fun remove() {
                removeBoolean(lastKey
                        ?:throw IllegalStateException())
                lastKey = null
            }
        }
    }


    protected fun htreeEntry(key:K, valueOrig:V) : MutableMap.MutableEntry<K,V>{

        return object : MutableMap.MutableEntry<K,V>{
            override val key: K
                get() = key

            override val value: V
                get() = valueCached ?: (this@HTreeMap.get(key)  ?: throw IllegalStateException("value in entry is null"))

            /** cached value, if null get value from map */
            private var valueCached:V? = valueOrig;

            override fun hashCode(): Int {
                //TODO validate hash code seed here?
                return keyHasher.hashCode(this.key!!, hashSeed) xor valueHasher.hashCode(this.value!!, hashSeed)
            }
            override fun setValue(newValue: V): V {
                valueCached = null;
                return put(key,newValue) ?: throw IllegalStateException("value in entry is null")
            }


            override fun equals(other: Any?): Boolean {
                if (other !is Map.Entry<*, *>)
                    return false
                @Suppress("UNCHECKED_CAST")
                val okey = other.key as K?
                @Suppress("UNCHECKED_CAST")
                val ovalue = other.value as V?
                try{

                    return okey!=null && ovalue!=null
                            && keyHasher.equals(key, okey)
                            && valueHasher.equals(this.value!!, ovalue)
                }catch(e:ClassCastException) {
                    return false
                }
            }

            override fun toString(): String {
                return "MapEntry[${key}=${value}]"
            }

        }
    }

    override fun hashCode(): Int {
        var h = 0
        val i = entries.iterator()
        while (i.hasNext())
            h += i.next().hashCode()
        return h
    }

    override fun equals(other: Any?): Boolean {
        if (other === this)
            return true

        if (other !is Map<*, *>)
            return false

        if (other.size != size)
            return false

        try {
            val i = entries.iterator()
            while (i.hasNext()) {
                val e = i.next()
                val key = e.key
                val value = e.value
                if (value == null) {
                    if (!(other.get(key) == null && other.containsKey(key)))
                        return false
                } else {
                    if (value != other.get(key))
                        return false
                }
            }
        } catch (unused: ClassCastException) {
            return false
        } catch (unused: NullPointerException) {
            return false
        }


        return true
    }


    override fun isClosed(): Boolean {
        return stores[0].isClosed
    }

    protected fun listenerNotify(key:K, oldValue:V?, newValue: V?, triggered:Boolean){
        if(modificationListeners!=null)
            for(l in modificationListeners)
                l.modify(key, oldValue, newValue, triggered)
    }


    protected fun valueUnwrap(segment:Int, wrappedValue:Any):V{
        if(valueInline)
            @Suppress("UNCHECKED_CAST")
            return wrappedValue as V
        if(CC.PARANOID)
          locks?.checkReadLocked(segment)
        return stores[segment].get(wrappedValue as Long, valueSerializer)
                ?: throw DBException.DataCorruption("linked value not found")
    }


    protected fun valueWrap(segment:Int, value:V):Any{
        if(CC.PARANOID)
            locks?.checkWriteLocked(segment)

        return if(valueInline) value as Any
        else return stores[segment].put(value, valueSerializer)
    }

    override fun forEach(action: BiConsumer<in K, in V>) {
        for(segment in 0 until segmentCount){
            segmentRead(segment){
                val store = stores[segment]
                indexTrees[segment].forEachValue { leafRecid ->
                    val leaf = leafGet(store, leafRecid)
                    for(i in 0 until leaf.size step 3){
                        @Suppress("UNCHECKED_CAST")
                        val key = leaf[i] as K
                        val value = valueUnwrap(segment, leaf[i+1])
                        action.accept(key, value)
                    }
                }
            }
        }
    }

    override fun forEachKey(procedure:  (K)->Unit) {
        for(segment in 0 until segmentCount){
            segmentRead(segment){
                val store = stores[segment]
                indexTrees[segment].forEachValue { leafRecid ->
                    val leaf = leafGet(store, leafRecid)
                    for(i in 0 until leaf.size step 3){
                        @Suppress("UNCHECKED_CAST")
                        val key = leaf[i] as K
                        procedure(key)
                    }
                }
            }
        }

    }

    override fun forEachValue(procedure:  (V)->Unit) {
        for(segment in 0 until segmentCount){
            segmentRead(segment){
                val store = stores[segment]
                indexTrees[segment].forEachValue { leafRecid ->
                    val leaf = leafGet(store, leafRecid)
                    for(i in 0 until leaf.size step 3){
                        val value = valueUnwrap(segment, leaf[i+1])
                        procedure(value)
                    }
                }
            }
        }
    }

    override fun verify(){
        val expireEnabled = expireCreateQueues!=null || expireUpdateQueues!=null || expireGetQueues!=null

        for(segment in 0 until segmentCount) {
            segmentRead(segment) {
                val tree = indexTrees[segment]
                if(tree is Verifiable)
                    tree.verify()

                val leafRecids = LongHashSet()
                val expireRecids = LongHashSet()

                tree.forEachKeyValue { index, leafRecid ->
                    if(leafRecids.add(leafRecid).not())
                        throw DBException.DataCorruption("Leaf recid referenced more then once")

                    if(tree.get(index)!=leafRecid)
                        throw DBException.DataCorruption("IndexTree corrupted")

                    val leaf = leafGet(stores[segment], leafRecid)

                    for(i in 0 until leaf.size step 3){
                        val key = leaf[i] as K
                        val hash = hash(key)
                        if(segment!=hashToSegment(hash))
                            throw DBException.DataCorruption("Hash To Segment")
                        if(index!=hashToIndex(hash))
                            throw DBException.DataCorruption("Hash To Index")
                        val value = valueUnwrap(segment, leaf[i+1])

                        val expireRecid = leaf[i+2]
                        if(expireEnabled.not() && expireRecid!=0L)
                            throw DBException.DataCorruption("Expire mismatch")
                        if(expireEnabled && expireRecid!=0L
                                && expireRecids.add(expireNodeRecidFor(expireRecid as Long)).not())
                            throw DBException.DataCorruption("Expire recid used multiple times")

                    }
                }

                fun queue(qq: Array<QueueLong>?){
                    if(qq==null)
                        return
                    val q = qq[segment]
                    q.verify()

                    q.forEach { expireRecid, leafRecid, timestamp ->
                        if(leafRecids.contains(leafRecid).not())
                            throw DBException.DataCorruption("leafRecid referenced from Queue not part of Map")
                        val leaf = leafGet(stores[segment], leafRecid)

                        //find entry by timestamp
                        var found = false;
                        for(i in 0 until leaf.size step 3){
                            if(expireRecid==expireNodeRecidFor(leaf[i+2] as Long)) {
                                found = true
                                break;
                            }
                        }
                        if(!found)
                            throw DBException.DataCorruption("value from Queue not found in leaf $leafRecid "+Arrays.toString(leaf))

                        if(expireRecids.remove(expireRecid).not())
                            throw DBException.DataCorruption("expireRecid not part of IndexTree")
                    }
                }
                queue(expireCreateQueues)
                queue(expireUpdateQueues)
                queue(expireGetQueues)

                if(expireRecids.isEmpty.not())
                    throw DBException.DataCorruption("Some expireRecids are not in queues")
            }
        }
    }


    /**
     * Closes underlying store and `DB`. It will release all related resources (file handles, background threads...)
     *
     * This will close not just this map, but also other collections in the same DB.
     */
    override fun close() {
        locks.lockWriteAll{
            closeable?.close()
        }
    }

    override fun assertThreadSafe() {
        super.assertThreadSafe()
        for(s in stores)
            s.assertThreadSafe()
    }

    /** calculates number of collisions and total size of this set.
     * @return pair, first is number of collisions, second is number of elements in map
     */
    fun calculateCollisionSize():Pair<Long,Long>{
        var collision = 0L
        var size = 0L

        for(segment in 0 until segmentCount) locks.lockRead(segment){
            indexTrees[segment].forEachValue { leafRecid ->
                val leaf = leafGet(stores[segment], leafRecid)
                size += leaf.size/3
                collision += leaf.size/3-1
            }
        }

        return Pair(collision, size)
    }

    private fun leafGet(store:Store, leafRecid:Long):Array<Any>{
        val leaf = store.get(leafRecid, leafSerializer)
                ?: throw DBException.DataCorruption("linked leaf not found")
        if(CC.PARANOID && leaf.size%3!=0)
            throw AssertionError()
        if(CC.PARANOID && leaf.size<3)
            throw AssertionError()
        return leaf
    }


}
