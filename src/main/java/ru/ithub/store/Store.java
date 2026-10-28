package ru.ithub.store;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class Store {
    public record Product(String id, String name, String category, BigDecimal price) {
        public Product { required(id); required(name); required(category); if (price == null || price.signum() < 0) throw new IllegalArgumentException("Цена"); }
    }
    public record Query(String name, String category, BigDecimal min, BigDecimal max) {
        public Query { if ((min != null && min.signum() < 0) || (max != null && max.signum() < 0) || (min != null && max != null && min.compareTo(max)>0)) throw new IllegalArgumentException("Диапазон цен"); }
    }
    public record Line(Product product, int quantity) { }
    public record Totals(BigDecimal subtotal, BigDecimal discount, BigDecimal tax, BigDecimal total) { }
    public record Address(String country, String city, String street, String postalCode) {
        public void validate() { required(country); required(city); required(street); if (postalCode == null || !postalCode.matches("[A-Za-z0-9 -]{3,12}")) throw new IllegalArgumentException("Индекс"); }
    }
    public sealed interface Payment permits Card, PayPal { void validate(Clock clock); }
    public record Card(String number, String holder, YearMonth expires, String cvv) implements Payment {
        public void validate(Clock clock) {
            required(holder);
            if (number == null || !number.matches("\\d{13,19}") || cvv == null || !cvv.matches("\\d{3,4}") || expires == null || expires.isBefore(YearMonth.now(clock))) throw new IllegalArgumentException("Данные карты");
            int sum=0; boolean twice=false;
            for(int i=number.length()-1;i>=0;i--) { int n=number.charAt(i)-'0'; if(twice) { n*=2; if(n>9)n-=9; } sum+=n; twice=!twice; }
            if(sum%10!=0) throw new IllegalArgumentException("Номер карты");
        }
    }
    public record PayPal(String email) implements Payment { public void validate(Clock clock) { validateEmail(email); } }
    public record Order(String id, String user, List<Line> lines, Totals totals, Address address, String paymentMethod) {
        public Order { lines=List.copyOf(lines); }
    }
    public interface PaymentGateway { boolean charge(Payment payment, BigDecimal amount); }
    public interface EmailSender { void send(String recipient, String subject, String body); }

    public static final class AuditLog {
        private final Path file;
        public AuditLog(Path file) throws IOException { this.file=file; if(file.toAbsolutePath().getParent()!=null) Files.createDirectories(file.toAbsolutePath().getParent()); }
        public synchronized void write(String operation, String user) {
            try { Files.writeString(file, Instant.now()+" "+operation+" user="+user.replaceAll("[\\r\\n]", "_")+System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch(IOException e) { throw new IllegalStateException("Не удалось записать журнал",e); }
        }
    }
    public final class Search {
        public List<Product> find(Query q) {
            log.write("SEARCH", "anonymous");
            String term=q.name()==null?"":q.name().strip().toLowerCase(Locale.ROOT);
            return products.values().stream().filter(p -> p.name().toLowerCase(Locale.ROOT).contains(term))
                .filter(p -> q.category()==null || q.category().isBlank() || p.category().equalsIgnoreCase(q.category()))
                .filter(p -> q.min()==null || p.price().compareTo(q.min())>=0)
                .filter(p -> q.max()==null || p.price().compareTo(q.max())<=0)
                .sorted(Comparator.comparingInt((Product p)-> relevance(p,term)).thenComparing(Product::price).thenComparing(Product::id)).toList();
        }
        private int relevance(Product p,String term) { String name=p.name().toLowerCase(Locale.ROOT); return name.equals(term)?0:name.startsWith(term)?1:2; }
    }
    public final class Cart {
        private final String user;
        private final Map<String,Integer> quantities=new LinkedHashMap<>();
        private Cart(String user) { this.user=user; }
        public synchronized void add(String id,int quantity) { validQuantity(quantity); product(id); quantities.put(id,Math.addExact(quantities.getOrDefault(id,0),quantity)); log.write("CART_ADD",user); }
        public synchronized void update(String id,int quantity) { validQuantity(quantity); if(!quantities.containsKey(id))throw new IllegalArgumentException("Товара нет в корзине"); quantities.put(id,quantity); log.write("CART_UPDATE",user); }
        public synchronized void remove(String id) { quantities.remove(id); log.write("CART_REMOVE",user); }
        public synchronized List<Line> lines() { log.write("CART_READ",user); return quantities.entrySet().stream().map(e->new Line(product(e.getKey()),e.getValue())).toList(); }
        public synchronized Totals totals() {
            BigDecimal subtotal=quantities.entrySet().stream().map(e->product(e.getKey()).price().multiply(BigDecimal.valueOf(e.getValue()))).reduce(BigDecimal.ZERO,BigDecimal::add);
            BigDecimal discount=money(subtotal.compareTo(discountThreshold)>=0?subtotal.multiply(discountRate):BigDecimal.ZERO);
            BigDecimal tax=money(subtotal.subtract(discount).multiply(taxRate));
            log.write("CART_TOTAL",user);
            return new Totals(money(subtotal),discount,tax,money(subtotal.subtract(discount).add(tax)));
        }
    }
    public final class Checkout {
        public Order place(String user,String email,Address address,Payment payment) {
            Cart cart=cart(user);
            synchronized(cart) {
                log.write("CHECKOUT_START",user);
                try {
                    validateEmail(email); Objects.requireNonNull(address,"Адрес").validate(); Objects.requireNonNull(payment,"Оплата").validate(clock);
                    List<Line> lines=cart.lines(); if(lines.isEmpty())throw new IllegalArgumentException("Пустая корзина");
                    Totals totals=cart.totals();
                    if(!gateway.charge(payment,totals.total()))throw new IllegalStateException("Платеж отклонен");
                    Order order=new Order(UUID.randomUUID().toString(),user,lines,totals,address,payment instanceof Card?"CARD":"PAYPAL");
                    orders.put(order.id(),order); cart.quantities.clear(); log.write("ORDER_CREATED",user);
                    String details="Заказ "+order.id()+"\n"+lines+"\nСумма: "+totals.total()+"\nДоставка: "+address;
                    try { emails.send(email,"Подтверждение заказа "+order.id(),details); log.write("EMAIL_SENT",user); }
                    catch(RuntimeException e) { pendingEmails.put(order.id(),new Notice(email,details)); log.write("EMAIL_PENDING",user); }
                    return order;
                } catch(RuntimeException e) { log.write("CHECKOUT_FAILED",user); throw e; }
            }
        }
        public synchronized void retryEmails() {
            for(var entry:pendingEmails.entrySet()) { Notice n=entry.getValue(); emails.send(n.email(),"Подтверждение заказа "+entry.getKey(),n.body()); pendingEmails.remove(entry.getKey()); log.write("EMAIL_RETRIED",orders.get(entry.getKey()).user()); }
        }
    }
    private record Notice(String email,String body) { }
    private final Map<String,Product> products;
    private final Map<String,Cart> carts=new ConcurrentHashMap<>();
    private final Map<String,Order> orders=new ConcurrentHashMap<>();
    private final Map<String,Notice> pendingEmails=new ConcurrentHashMap<>();
    private final BigDecimal taxRate,discountRate,discountThreshold;
    private final AuditLog log; private final PaymentGateway gateway; private final EmailSender emails; private final Clock clock;
    public final Search search=new Search(); public final Checkout checkout=new Checkout();
    public Store(List<Product> catalog,BigDecimal taxRate,BigDecimal discountRate,BigDecimal discountThreshold,AuditLog log,PaymentGateway gateway,EmailSender emails,Clock clock) {
        if(taxRate.signum()<0 || discountRate.signum()<0 || discountRate.compareTo(BigDecimal.ONE)>0 || discountThreshold.signum()<0)throw new IllegalArgumentException("Ставки");
        Map<String,Product> map=new HashMap<>(); for(Product p:catalog)if(map.put(p.id(),p)!=null)throw new IllegalArgumentException("Повтор ID");
        this.products=Map.copyOf(map); this.taxRate=taxRate; this.discountRate=discountRate; this.discountThreshold=discountThreshold; this.log=log; this.gateway=gateway; this.emails=emails; this.clock=clock;
    }
    public Cart cart(String user) { required(user); log.write("CART_ACCESS",user); return carts.computeIfAbsent(user,Cart::new); }
    public List<Order> orders(String user) { required(user); log.write("ORDERS_READ",user); return orders.values().stream().filter(o->o.user().equals(user)).toList(); }
    private Product product(String id) { Product p=products.get(id); if(p==null)throw new IllegalArgumentException("Неизвестный товар"); return p; }
    private static void validQuantity(int q) { if(q<=0)throw new IllegalArgumentException("Количество должно быть положительным"); }
    private static void required(String s) { if(s==null || s.isBlank())throw new IllegalArgumentException("Обязательное поле"); }
    private static void validateEmail(String s) { if(s==null || !s.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))throw new IllegalArgumentException("Email"); }
    private static BigDecimal money(BigDecimal n) { return n.setScale(2,RoundingMode.HALF_UP); }
}
