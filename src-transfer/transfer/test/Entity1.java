package transfer.test;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.persistence.Column;
import javax.persistence.Id;
import javax.persistence.Transient;

import com.baidu.bjf.remoting.protobuf.FieldType;
import com.baidu.bjf.remoting.protobuf.annotation.Protobuf;
import dbcache.EntityInitializer;
import dbcache.IEntity;
import dbcache.anno.ChangeFields;
import dbcache.anno.JsonType;
import org.hibernate.annotations.Index;
import transfer.anno.Transferable;

//@MappedSuperclass
@Transferable(id = 1)
public class Entity1 implements EntityInitializer, IEntity<Long>, Serializable {

	public static final String NUM_INDEX = "num_idx";

	@Id
	@Protobuf(fieldType = FieldType.INT64, order = 1, required = true)
	public Long id;

	@Protobuf(fieldType = FieldType.INT32, order = 2, required = true)
	private int uid=212;

	@Index(name=NUM_INDEX)
	@Protobuf(fieldType = FieldType.INT32, order = 3, required = true)
	public int num=5;

	@Protobuf(fieldType = FieldType.STRING, order = 4, required = true)
	private String name;

	private Entity1 next;

	@JsonType
	@Column(columnDefinition="varchar(255) null comment '已经领取过的奖励Id'")
	private HashSet<Long> friends = new HashSet<Long>();

	public Entity1() {
//		doAfterLoad();
//		doAfterLoad();
	}


	public int getNum() {
		return num;
	}


	public void setNum(int num) {
		this.num = num;
	}

//	@ChangeIndexes({ "num_idx" })
//	@ChangeFields({"num"})
	public int addNum(int num) {
		this.num += num;
		return this.num;
	}

	@ChangeFields({"friends"})
	public void combine(Entity1 other, boolean addMap) {
		this.friends.addAll(other.getFriends());
	}

	public int getUid() {
		return uid;
	}

	public void setUid(int uid) {
		this.uid = uid;
	}

    @Override
    public void doAfterLoad() {

    }

    @Override
	public void doBeforePersist() {

	}


	@Override
	public Long getId() {
		return id;
	}

	@Override
	public void setId(Long id) {
		this.id = id;
	}

	@Override
	public int hashCode() {
		return 305668771 + 1793910479 * this.getId().hashCode();
	}



	@Override
	public String toString() {
		return "测试";
	}

	@Override
	public boolean equals(Object obj) {
		if(this == obj) {
			return true;
		}
		if(obj == null) {
			return false;
		}
		if(!(obj instanceof Entity1)) {
			return false;
		}
		Entity1 target = (Entity1) obj;
		return this.id.equals(target.id);
	}

	public static void main(String[] args) {

		for(Method method : Entity1.class.getDeclaredMethods() ) {
			System.out.println(method);
		}

	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public HashSet<Long> getFriends() {
		return friends;
	}

	public void setFriends(HashSet<Long> friends) {
		this.friends = friends;
	}

	public void combine(Object object, boolean addMap) {
		
	}

    public Entity1 getNext() {
        return next;
    }

    public void setNext(Entity1 next) {
        this.next = next;
    }
}