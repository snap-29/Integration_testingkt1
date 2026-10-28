package ru.ithub.store;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static ru.ithub.store.Store.*;

class StoreIntegrationTest {
 @TempDir Path temp;
 Store store; AtomicInteger charges; List<String> messages; boolean decline, mailFailure;
 static BigDecimal bd(String s){return new BigDecimal(s);}
 final Address address=new Address("Россия","Москва","Ленина, 1","123456");
 @BeforeEach void setup() throws Exception {
  charges=new AtomicInteger(); messages=new CopyOnWriteArrayList<>();
  store=new Store(List.of(new Product("1","Phone","Electronics",bd("1000")),new Product("2","Phone Pro","Electronics",bd("1500")),new Product("3","Case Phone","Accessories",bd("100")),new Product("4","Phone Mini","Electronics",bd("800"))),bd("0.20"),bd("0.10"),bd("2000"),new AuditLog(temp.resolve("operations.log")),(payment,amount)->{charges.incrementAndGet();return !decline;},(to,subject,body)->{if(mailFailure)throw new IllegalStateException();messages.add(to+subject+body);},Clock.fixed(Instant.parse("2026-10-28T12:00:00Z"),ZoneOffset.UTC));
 }
 Order buy(String user,Payment payment){return store.checkout.place(user,"buyer@example.com",address,payment);}
 @Test void searchFiltersAndSortsThenAddsToCart(){
  var found=store.search.find(new Query("PHONE","Electronics",bd("800"),bd("1500")));
  assertEquals(List.of("1","4","2"),found.stream().map(Product::id).toList());
  store.cart("u").add(found.getFirst().id(),2); assertEquals(bd("2160.00"),store.cart("u").totals().total());
 }
 @Test void categoryOnly(){assertEquals(1,store.search.find(new Query(null,"Accessories",null,null)).size());}
 @Test void exactPriceBoundary(){assertEquals("4",store.search.find(new Query(null,null,bd("800"),bd("800"))).getFirst().id());}
 @Test void noMatches(){assertTrue(store.search.find(new Query("missing",null,null,null)).isEmpty());}
 @Test void invalidRange(){assertThrows(IllegalArgumentException.class,()->new Query(null,null,bd("10"),bd("1")));}
 @Test void cartChangesRecalculate(){var c=store.cart("u");c.add("1",1);assertEquals(bd("1200.00"),c.totals().total());c.add("1",1);assertEquals(2,c.lines().getFirst().quantity());assertEquals(bd("200.00"),c.totals().discount());assertEquals(bd("360.00"),c.totals().tax());c.update("1",3);assertEquals(bd("3240.00"),c.totals().total());c.remove("1");assertEquals(bd("0.00"),c.totals().total());}
 @Test void invalidQuantityAndUnknownProduct(){var c=store.cart("u");assertThrows(IllegalArgumentException.class,()->c.add("1",0));assertThrows(IllegalArgumentException.class,()->c.add("missing",1));c.add("1",1);assertThrows(IllegalArgumentException.class,()->c.update("1",-1));assertEquals(1,c.lines().getFirst().quantity());}
 @Test void cardCheckoutSendsDetails(){store.cart("u").add("1",2);var o=buy("u",new Card("4111111111111111","Test User",YearMonth.of(2030,1),"123"));assertEquals("CARD",o.paymentMethod());assertEquals(1,charges.get());assertEquals(List.of(o),store.orders("u"));assertTrue(store.cart("u").lines().isEmpty());assertTrue(messages.getFirst().contains(o.id()));assertTrue(messages.getFirst().contains("2160.00"));assertTrue(messages.getFirst().contains("Phone"));assertTrue(messages.getFirst().contains("Москва"));}
 @Test void paypalCheckout(){store.cart("u").add("3",1);assertEquals("PAYPAL",buy("u",new PayPal("payer@example.com")).paymentMethod());assertEquals(1,messages.size());}
 @Test void invalidPaymentsDoNotCharge(){store.cart("u").add("1",1);for(Payment payment:List.of(new PayPal("bad"),new Card("4111111111111112","Test",YearMonth.of(2030,1),"123"),new Card("4111111111111111","Test",YearMonth.of(2020,1),"123"),new Card("4111111111111111","Test",YearMonth.of(2030,1),"x")))assertThrows(IllegalArgumentException.class,()->buy("u",payment));assertEquals(0,charges.get());assertEquals(1,store.cart("u").lines().size());}
 @Test void invalidAddressAndEmail(){store.cart("u").add("1",1);assertThrows(IllegalArgumentException.class,()->store.checkout.place("u","a@example.com",new Address("Россия","","ул. 1","123456"),new PayPal("a@example.com")));assertThrows(IllegalArgumentException.class,()->store.checkout.place("u","bad",address,new PayPal("a@example.com")));assertEquals(0,charges.get());}
 @Test void emptyCart(){assertThrows(IllegalArgumentException.class,()->buy("u",new PayPal("a@example.com")));assertEquals(0,charges.get());}
 @Test void declinedPaymentKeepsCart(){decline=true;store.cart("u").add("1",1);assertThrows(IllegalStateException.class,()->buy("u",new PayPal("a@example.com")));assertTrue(store.orders("u").isEmpty());assertTrue(messages.isEmpty());assertEquals(1,store.cart("u").lines().size());}
 @Test void failedEmailCanBeRetriedWithoutSecondCharge(){mailFailure=true;store.cart("u").add("1",1);var o=buy("u",new PayPal("a@example.com"));mailFailure=false;store.checkout.retryEmails();store.checkout.retryEmails();assertEquals(1,charges.get());assertEquals(1,messages.size());assertTrue(messages.getFirst().contains(o.id()));}
 @Test void concurrentUsersAreIsolated() throws Exception {try(var pool=Executors.newFixedThreadPool(8)){List<Callable<Order>> tasks=new ArrayList<>();for(int i=0;i<24;i++){String user="user"+i;tasks.add(()->{var p=store.search.find(new Query("Phone",null,null,null)).getFirst();store.cart(user).add(p.id(),1);return buy(user,new PayPal("a@example.com"));});}for(var future:pool.invokeAll(tasks)){var order=future.get(10,TimeUnit.SECONDS);assertEquals(1,store.orders(order.user()).size());assertEquals(bd("1200.00"),order.totals().total());}}assertEquals(24,charges.get());assertEquals(24,messages.size());}
 @Test void concurrentAddsAreNotLost() throws Exception {var c=store.cart("u");try(var pool=Executors.newFixedThreadPool(8)){List<Callable<Void>> tasks=new ArrayList<>();for(int i=0;i<100;i++)tasks.add(()->{c.add("3",1);return null;});for(var f:pool.invokeAll(tasks))f.get();}assertEquals(100,c.lines().getFirst().quantity());}
 @Test void operationsAreLoggedWithoutPaymentSecrets() throws Exception {store.search.find(new Query(null,null,null,null));var c=store.cart("u");c.add("1",1);c.update("1",2);c.remove("1");c.add("1",1);buy("u",new Card("4111111111111111","Test",YearMonth.of(2030,1),"987"));String log=Files.readString(temp.resolve("operations.log"));for(String event:List.of("SEARCH","CART_ADD","CART_UPDATE","CART_REMOVE","CART_TOTAL","CHECKOUT_START","ORDER_CREATED","EMAIL_SENT"))assertTrue(log.contains(event));assertFalse(log.contains("4111111111111111"));assertFalse(log.contains("987"));}
}
