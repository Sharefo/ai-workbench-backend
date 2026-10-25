package com.sharefo.aiworkbench.sqllab.dataset;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.springframework.stereotype.Component;

/**
 * 内置电商运营场景数据集。使用固定随机种子，保证同一份数据可复现。
 */
@Component
public class EcommerceDataset {

	private static final String[] DDL = {
			"CREATE TABLE dim_channel (id INT NOT NULL PRIMARY KEY, channel_name VARCHAR(32) NOT NULL,"
					+ " channel_type VARCHAR(16) NOT NULL, is_paid TINYINT NOT NULL)",
			"CREATE TABLE dim_date (date_id DATE NOT NULL PRIMARY KEY, y INT NOT NULL, m INT NOT NULL,"
					+ " d INT NOT NULL, quarter_no INT NOT NULL, week_no INT NOT NULL, day_of_week INT NOT NULL,"
					+ " is_weekend TINYINT NOT NULL, is_promotion TINYINT NOT NULL)",
			"CREATE TABLE categories (id INT NOT NULL PRIMARY KEY, parent_id INT NULL,"
					+ " category_name VARCHAR(32) NOT NULL)",
			"CREATE TABLE users (id BIGINT NOT NULL PRIMARY KEY, nickname VARCHAR(32) NOT NULL,"
					+ " gender VARCHAR(4) NOT NULL, city VARCHAR(32) NOT NULL, member_level VARCHAR(16) NOT NULL,"
					+ " register_date DATE NOT NULL, is_new TINYINT NOT NULL)",
			"CREATE TABLE products (id INT NOT NULL PRIMARY KEY, product_name VARCHAR(64) NOT NULL,"
					+ " category_id INT NOT NULL, price DECIMAL(10,2) NOT NULL, cost DECIMAL(10,2) NOT NULL,"
					+ " stock INT NOT NULL, on_sale_date DATE NOT NULL)",
			"CREATE TABLE orders (id BIGINT NOT NULL PRIMARY KEY, order_no VARCHAR(24) NOT NULL,"
					+ " user_id BIGINT NOT NULL, channel_id INT NOT NULL, status VARCHAR(16) NOT NULL,"
					+ " amount DECIMAL(12,2) NOT NULL, created_at TIMESTAMP NOT NULL, paid_at TIMESTAMP NULL)",
			"CREATE TABLE order_items (id BIGINT NOT NULL PRIMARY KEY, order_id BIGINT NOT NULL,"
					+ " product_id INT NOT NULL, quantity INT NOT NULL, unit_price DECIMAL(10,2) NOT NULL,"
					+ " discount_amount DECIMAL(10,2) NOT NULL)",
			"CREATE TABLE payments (id BIGINT NOT NULL PRIMARY KEY, order_id BIGINT NOT NULL,"
					+ " pay_method VARCHAR(16) NOT NULL, amount DECIMAL(12,2) NOT NULL,"
					+ " status VARCHAR(16) NOT NULL, paid_at TIMESTAMP NOT NULL)",
			"CREATE TABLE campaigns (id INT NOT NULL PRIMARY KEY, campaign_name VARCHAR(48) NOT NULL,"
					+ " channel_id INT NOT NULL, start_date DATE NOT NULL, end_date DATE NOT NULL,"
					+ " cost DECIMAL(12,2) NOT NULL, impressions INT NOT NULL, clicks INT NOT NULL)",
			"CREATE TABLE user_events (id BIGINT NOT NULL PRIMARY KEY, user_id BIGINT NOT NULL,"
					+ " event_type VARCHAR(16) NOT NULL, event_date DATE NOT NULL, product_id INT NULL)"
	};

	private static final String[] COMMENTS = {
			"COMMENT ON TABLE dim_channel IS '渠道维表'",
			"COMMENT ON TABLE dim_date IS '日期维表，近三年'",
			"COMMENT ON TABLE categories IS '商品类目，二级树'",
			"COMMENT ON TABLE users IS '用户主表'",
			"COMMENT ON TABLE products IS '商品主表'",
			"COMMENT ON TABLE orders IS '订单主表'",
			"COMMENT ON TABLE order_items IS '订单明细'",
			"COMMENT ON TABLE payments IS '支付流水'",
			"COMMENT ON TABLE campaigns IS '投放活动'",
			"COMMENT ON TABLE user_events IS '用户行为事件'",
			"COMMENT ON COLUMN orders.status IS '订单状态：待付款/已付款/已发货/已完成/已取消/已退款'",
			"COMMENT ON COLUMN orders.amount IS '订单实付金额'",
			"COMMENT ON COLUMN orders.user_id IS '下单用户id，关联 users.id'",
			"COMMENT ON COLUMN orders.channel_id IS '渠道id，关联 dim_channel.id'",
			"COMMENT ON COLUMN orders.created_at IS '下单时间'",
			"COMMENT ON COLUMN order_items.order_id IS '所属订单id，关联 orders.id'",
			"COMMENT ON COLUMN order_items.product_id IS '商品id，关联 products.id'",
			"COMMENT ON COLUMN payments.order_id IS '订单id，关联 orders.id'",
			"COMMENT ON COLUMN products.category_id IS '类目id，关联 categories.id'",
			"COMMENT ON COLUMN categories.parent_id IS '父类目id，关联 categories.id，为空表示一级类目'",
			"COMMENT ON COLUMN campaigns.channel_id IS '投放渠道id，关联 dim_channel.id'",
			"COMMENT ON COLUMN user_events.user_id IS '用户id，关联 users.id'",
			"COMMENT ON COLUMN user_events.product_id IS '商品id，关联 products.id'",
			"COMMENT ON COLUMN users.member_level IS '会员等级：普通/银卡/金卡/钻石'",
			"COMMENT ON COLUMN users.is_new IS '是否新客：1 是 0 否'",
			"COMMENT ON COLUMN dim_channel.is_paid IS '是否付费渠道：1 是 0 否'"
	};

	private static final String[][] CHANNELS = {
			{"1", "自然流量", "免费", "0"},
			{"2", "搜索广告", "付费", "1"},
			{"3", "信息流投放", "付费", "1"},
			{"4", "达人直播", "付费", "1"},
			{"5", "私域社群", "免费", "0"},
			{"6", "老客召回", "免费", "0"},
			{"7", "联盟推广", "付费", "1"},
			{"8", "线下门店", "免费", "0"}
	};

	private static final String[] TOP_CATEGORIES =
			{"服饰鞋包", "美妆个护", "食品生鲜", "数码家电", "家居日用", "母婴玩具"};

	private static final String[] CITIES =
			{"北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "西安", "南京", "重庆", "苏州", "长沙", "青岛", "天津"};

	private static final String[] MEMBER_LEVELS = {"普通", "银卡", "金卡", "钻石"};

	private static final String[] ORDER_STATUS = {"已完成", "已完成", "已完成", "已完成", "已完成", "已完成", "已发货", "已付款",
			"待付款", "已取消", "已退款"};

	private static final String[] PAY_METHODS = {"微信支付", "支付宝"};

	private static final String[] EVENT_TYPES = {"浏览", "浏览", "浏览", "加购", "收藏", "下单"};

	private static final String[] CAMPAIGN_PREFIX = {"双11", "618", "年货节", "开学季", "女神节", "夏季清凉", "秋季焕新", "会员日"};

	public void seed(Connection connection, String scale) throws SQLException {
		Scale s = Scale.of(scale);
		try (Statement st = connection.createStatement()) {
			for (String ddl : DDL) {
				st.execute(ddl);
			}
			for (String comment : COMMENTS) {
				st.execute(comment);
			}
		}
		Random rnd = new Random(42);
		LocalDate anchor = LocalDate.now();

		seedChannels(connection);
		seedDates(connection, anchor);
		seedCategories(connection);
		seedUsers(connection, rnd, s, anchor);
		seedProducts(connection, rnd, s, anchor);
		List<OrderHead> orders = seedOrders(connection, rnd, s, anchor);
		seedOrderItems(connection, rnd, s, orders);
		seedPayments(connection, rnd, orders);
		seedCampaigns(connection, rnd, s, anchor);
		seedUserEvents(connection, rnd, s, anchor);
	}

	private void seedChannels(Connection c) throws SQLException {
		batch(c, "INSERT INTO dim_channel (id, channel_name, channel_type, is_paid) VALUES (?, ?, ?, ?)",
				CHANNELS.length, (ps, i) -> {
					String[] row = CHANNELS[i];
					ps.setInt(1, Integer.parseInt(row[0]));
					ps.setString(2, row[1]);
					ps.setString(3, row[2]);
					ps.setInt(4, Integer.parseInt(row[3]));
				});
	}

	private void seedDates(Connection c, LocalDate anchor) throws SQLException {
		LocalDate start = anchor.minusYears(3);
		int days = (int) (anchor.toEpochDay() - start.toEpochDay()) + 1;
		batch(c, "INSERT INTO dim_date (date_id, y, m, d, quarter_no, week_no, day_of_week, is_weekend,"
				+ " is_promotion) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", days, (ps, i) -> {
			LocalDate day = start.plusDays(i);
			int dow = day.getDayOfWeek().getValue();
			ps.setDate(1, Date.valueOf(day));
			ps.setInt(2, day.getYear());
			ps.setInt(3, day.getMonthValue());
			ps.setInt(4, day.getDayOfMonth());
			ps.setInt(5, (day.getMonthValue() - 1) / 3 + 1);
			ps.setInt(6, day.getDayOfYear() / 7 + 1);
			ps.setInt(7, dow);
			ps.setInt(8, dow >= 6 ? 1 : 0);
			ps.setInt(9, (day.getMonthValue() == 6 && day.getDayOfMonth() >= 15 && day.getDayOfMonth() <= 20)
					|| (day.getMonthValue() == 11 && day.getDayOfMonth() >= 1 && day.getDayOfMonth() <= 12)
					|| (day.getMonthValue() == 12 && day.getDayOfMonth() >= 20) ? 1 : 0);
		});
	}

	private void seedCategories(Connection c) throws SQLException {
		batch(c, "INSERT INTO categories (id, parent_id, category_name) VALUES (?, ?, ?)",
				TOP_CATEGORIES.length * 6, (ps, i) -> {
			int top = i / 6;
			int child = i % 6;
			ps.setInt(1, i + 1);
			if (child == 0) {
				ps.setNull(2, java.sql.Types.INTEGER);
				ps.setString(3, TOP_CATEGORIES[top]);
			}
			else {
				ps.setInt(2, top * 6 + 1);
				ps.setString(3, TOP_CATEGORIES[top] + "-" + (child + 1) + "级");
			}
		});
	}

	private void seedUsers(Connection c, Random rnd, Scale s, LocalDate anchor) throws SQLException {
		batch(c, "INSERT INTO users (id, nickname, gender, city, member_level, register_date, is_new)"
				+ " VALUES (?, ?, ?, ?, ?, ?, ?)", s.users, (ps, i) -> {
			String city = CITIES[rnd.nextInt(CITIES.length)];
			int levelRoll = rnd.nextInt(100);
			String level = levelRoll < 60 ? MEMBER_LEVELS[0] : levelRoll < 82 ? MEMBER_LEVELS[1]
					: levelRoll < 95 ? MEMBER_LEVELS[2] : MEMBER_LEVELS[3];
			int daysAgo = rnd.nextInt(1095);
			LocalDate reg = anchor.minusDays(daysAgo);
			ps.setLong(1, i + 1L);
			ps.setString(2, "用户" + (i + 1));
			ps.setString(3, rnd.nextInt(2) == 0 ? "男" : "女");
			ps.setString(4, city);
			ps.setString(5, level);
			ps.setDate(6, Date.valueOf(reg));
			ps.setInt(7, daysAgo <= 90 ? 1 : 0);
		});
	}

	private void seedProducts(Connection c, Random rnd, Scale s, LocalDate anchor) throws SQLException {
		batch(c, "INSERT INTO products (id, product_name, category_id, price, cost, stock, on_sale_date)"
				+ " VALUES (?, ?, ?, ?, ?, ?, ?)", s.products, (ps, i) -> {
			int categoryId = rnd.nextInt(36) + 1;
			double price = Math.round((20 + Math.pow(rnd.nextDouble(), 2.2) * 3800) * 100.0) / 100.0;
			double cost = Math.round(price * (0.45 + rnd.nextDouble() * 0.25) * 100.0) / 100.0;
			ps.setInt(1, i + 1);
			ps.setString(2, "商品-" + String.format("%04d", i + 1));
			ps.setInt(3, categoryId);
			ps.setBigDecimal(4, BigDecimal.valueOf(price));
			ps.setBigDecimal(5, BigDecimal.valueOf(cost));
			ps.setInt(6, rnd.nextInt(5000));
			ps.setDate(7, Date.valueOf(anchor.minusDays(rnd.nextInt(730))));
		});
	}

	private List<OrderHead> seedOrders(Connection c, Random rnd, Scale s, LocalDate anchor) throws SQLException {
		List<OrderHead> heads = new ArrayList<>(s.orders);
		for (int i = 0; i < s.orders; i++) {
			int daysAgo = (int) (Math.pow(rnd.nextDouble(), 1.35) * 365);
			int hour = rnd.nextInt(24);
			int minute = rnd.nextInt(60);
			LocalDateTime created = anchor.minusDays(daysAgo).atTime(hour, minute, rnd.nextInt(60));
			String status = ORDER_STATUS[rnd.nextInt(ORDER_STATUS.length)];
			OrderHead h = new OrderHead();
			h.id = i + 1L;
			h.orderNo = String.format("NO%s%06d", created.toLocalDate().toString().replace("-", ""), i + 1);
			h.userId = 1L + rnd.nextInt(s.users);
			h.channelId = 1 + rnd.nextInt(8);
			h.status = status;
			h.createdAt = created;
			h.paidAt = status.equals("待付款") || status.equals("已取消") ? null
					: created.plusMinutes(1 + rnd.nextInt(120));
			heads.add(h);
		}
		batch(c, "INSERT INTO orders (id, order_no, user_id, channel_id, status, amount, created_at, paid_at)"
				+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", s.orders, (ps, i) -> {
			OrderHead h = heads.get(i);
			ps.setLong(1, h.id);
			ps.setString(2, h.orderNo);
			ps.setLong(3, h.userId);
			ps.setInt(4, h.channelId);
			ps.setString(5, h.status);
			ps.setBigDecimal(6, BigDecimal.ZERO);
			ps.setTimestamp(7, Timestamp.valueOf(h.createdAt));
			if (h.paidAt == null) {
				ps.setNull(8, java.sql.Types.TIMESTAMP);
			}
			else {
				ps.setTimestamp(8, Timestamp.valueOf(h.paidAt));
			}
		});
		return heads;
	}

	/** 先写明细，再把金额回写到订单，保证 SUM(order_items) 与 orders.amount 自洽 */
	private void seedOrderItems(Connection c, Random rnd, Scale s, List<OrderHead> heads) throws SQLException {
		List<Object[]> items = new ArrayList<>();
		long itemId = 1;
		for (OrderHead h : heads) {
			int lines = 1 + rnd.nextInt(4);
			BigDecimal total = BigDecimal.ZERO;
			for (int k = 0; k < lines; k++) {
				int productId = 1 + rnd.nextInt(s.products);
				int qty = 1 + rnd.nextInt(3);
				double unitPrice = Math.round((20 + Math.pow(rnd.nextDouble(), 2.2) * 3800) * 100.0) / 100.0;
				double discount = rnd.nextInt(4) == 0 ? Math.round(unitPrice * qty * 0.1 * 100.0) / 100.0 : 0.0;
				items.add(new Object[] {itemId++, h.id, productId, qty, BigDecimal.valueOf(unitPrice),
						BigDecimal.valueOf(discount)});
				total = total.add(BigDecimal.valueOf(unitPrice * qty - discount));
			}
			h.amount = total.setScale(2, java.math.RoundingMode.HALF_UP);
		}
		batch(c, "INSERT INTO order_items (id, order_id, product_id, quantity, unit_price, discount_amount)"
				+ " VALUES (?, ?, ?, ?, ?, ?)", items.size(), (ps, i) -> {
			Object[] r = items.get(i);
			ps.setLong(1, (Long) r[0]);
			ps.setLong(2, (Long) r[1]);
			ps.setInt(3, (Integer) r[2]);
			ps.setInt(4, (Integer) r[3]);
			ps.setBigDecimal(5, (BigDecimal) r[4]);
			ps.setBigDecimal(6, (BigDecimal) r[5]);
		});
		batch(c, "UPDATE orders SET amount = ? WHERE id = ?", heads.size(), (ps, i) -> {
			ps.setBigDecimal(1, heads.get(i).amount);
			ps.setLong(2, heads.get(i).id);
		});
	}

	private void seedPayments(Connection c, Random rnd, List<OrderHead> heads) throws SQLException {
		List<OrderHead> paid = new ArrayList<>();
		for (OrderHead h : heads) {
			if (h.paidAt != null) {
				paid.add(h);
			}
		}
		batch(c, "INSERT INTO payments (id, order_id, pay_method, amount, status, paid_at)"
				+ " VALUES (?, ?, ?, ?, ?, ?)", paid.size(), (ps, i) -> {
			OrderHead h = paid.get(i);
			boolean refunded = "已退款".equals(h.status);
			ps.setLong(1, i + 1L);
			ps.setLong(2, h.id);
			ps.setString(3, PAY_METHODS[rnd.nextInt(PAY_METHODS.length)]);
			ps.setBigDecimal(4, h.amount);
			ps.setString(5, refunded ? "已退款" : "支付成功");
			ps.setTimestamp(6, Timestamp.valueOf(h.paidAt));
		});
	}

	private void seedCampaigns(Connection c, Random rnd, Scale s, LocalDate anchor) throws SQLException {
		batch(c, "INSERT INTO campaigns (id, campaign_name, channel_id, start_date, end_date, cost,"
				+ " impressions, clicks) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", s.campaigns, (ps, i) -> {
			LocalDate start = anchor.minusDays(rnd.nextInt(540));
			int impressions = 10000 + rnd.nextInt(900000);
			int clicks = (int) (impressions * (0.01 + rnd.nextDouble() * 0.08));
			ps.setInt(1, i + 1);
			ps.setString(2, CAMPAIGN_PREFIX[rnd.nextInt(CAMPAIGN_PREFIX.length)] + "-第" + (i + 1) + "期");
			ps.setInt(3, 1 + rnd.nextInt(8));
			ps.setDate(4, Date.valueOf(start));
			ps.setDate(5, Date.valueOf(start.plusDays(3 + rnd.nextInt(20))));
			ps.setBigDecimal(6, BigDecimal.valueOf(Math.round((clicks * (0.5 + rnd.nextDouble() * 3)) * 100.0) / 100.0));
			ps.setInt(7, impressions);
			ps.setInt(8, clicks);
		});
	}

	private void seedUserEvents(Connection c, Random rnd, Scale s, LocalDate anchor) throws SQLException {
		batch(c, "INSERT INTO user_events (id, user_id, event_type, event_date, product_id)"
				+ " VALUES (?, ?, ?, ?, ?)", s.events, (ps, i) -> {
			ps.setLong(1, i + 1L);
			ps.setLong(2, 1L + rnd.nextInt(s.users));
			ps.setString(3, EVENT_TYPES[rnd.nextInt(EVENT_TYPES.length)]);
			ps.setDate(4, Date.valueOf(anchor.minusDays(rnd.nextInt(180))));
			ps.setInt(5, 1 + rnd.nextInt(s.products));
		});
	}

	private interface Binder {
		void bind(PreparedStatement ps, int index) throws SQLException;
	}

	private void batch(Connection c, String sql, int rows, Binder binder) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			for (int i = 0; i < rows; i++) {
				binder.bind(ps, i);
				ps.addBatch();
				if (i % 1000 == 999) {
					ps.executeBatch();
				}
			}
			ps.executeBatch();
		}
	}

	public static class OrderHead {
		long id;
		String orderNo;
		long userId;
		int channelId;
		String status;
		BigDecimal amount = BigDecimal.ZERO;
		LocalDateTime createdAt;
		LocalDateTime paidAt;
	}

	public static class Scale {

		final int users;
		final int products;
		final int orders;
		final int campaigns;
		final int events;

		Scale(int users, int products, int orders, int campaigns, int events) {
			this.users = users;
			this.products = products;
			this.orders = orders;
			this.campaigns = campaigns;
			this.events = events;
		}

		public static Scale of(String name) {
			if ("medium".equalsIgnoreCase(name)) {
				return new Scale(20000, 2000, 120000, 400, 400000);
			}
			if ("large".equalsIgnoreCase(name)) {
				return new Scale(100000, 8000, 600000, 1000, 2000000);
			}
			return new Scale(2000, 300, 12000, 120, 40000);
		}
	}
}
