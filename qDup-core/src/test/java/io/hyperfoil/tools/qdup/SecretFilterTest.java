package io.hyperfoil.tools.qdup;

import io.hyperfoil.tools.qdup.cmd.Dispatcher;
import io.hyperfoil.tools.qdup.cmd.SpyContext;
import io.hyperfoil.tools.qdup.cmd.impl.Regex;
import io.hyperfoil.tools.qdup.config.RunConfig;
import io.hyperfoil.tools.qdup.config.RunConfigBuilder;
import io.hyperfoil.tools.qdup.config.yaml.Parser;
import io.hyperfoil.tools.yaup.json.Json;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.util.Iterator;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

import static io.hyperfoil.tools.qdup.SecretFilter.REPLACEMENT;
import static org.junit.Assert.*;

public class SecretFilterTest extends SshTestBase {

   @Test
   public void secret_order(){
      SecretFilter filter = new SecretFilter();
      filter.addSecret("barb");
      filter.addSecret("bara");
      filter.addSecret("barbar");

      Iterator<String> iter = filter.getSecrets().iterator();
      assertTrue(iter.hasNext());
      String current = iter.next();
      assertEquals("first entry should be longest","barbar",current);
      assertTrue(iter.hasNext());
      current = iter.next();
      assertEquals("second entry should be alphabetical","bara",current);
      current = iter.next();
      assertEquals("third entry should be alphabetical","barb",current);

   }
   @Test
   public void secret_contains_another_secret(){
      SecretFilter filter = new SecretFilter();
      filter.addSecret("bar");
      filter.addSecret("foobar");
      String output = filter.filter("foobar");
      assertEquals("full input should be filtered: "+filter.getSecrets(), REPLACEMENT,output);
   }

   @Test
   public void add_empty_filter(){
      SecretFilter filter = new SecretFilter();
      filter.addSecret("");
      assertTrue("adding an empty string filter should be rejected",filter.getSecrets().isEmpty());
   }

   @Test
   public void filter_with_curly_brackets(){
      SecretFilter filter = new SecretFilter();
      filter.addSecret("{\"key\":\"value\"}");
      try {
         String output = filter.filter("foo {\"key\":\"value\"} bar");
         assertEquals("output should remove json","foo "+REPLACEMENT+" bar",output);
      }catch(Exception e){
         fail("should not throw an exception when filtering\n"+e.getMessage());
      }
   }

   @Test
   public void filter_repeated(){
      SecretFilter filter = new SecretFilter();
      filter.addSecret("foo");
      try {
         String output = filter.filter("foobarfoobarfoo");
         assertEquals("output should remove secret",REPLACEMENT+"bar"+REPLACEMENT+"bar"+REPLACEMENT,output);
      }catch(Exception e){
         fail("should not throw an exception when filtering\n"+e.getMessage());
      }
   }

   @Test
   public void detect_secret_in_state(){
      State state = new State("");

      state.set(SecretFilter.SECRET_NAME_PREFIX+"foo","BAR");

      SecretFilter secretFilter = state.getSecretFilter();

      assertEquals("expect 1 secret",1,secretFilter.size());
      assertTrue("state should add key without prefix: "+state.getKeys(),state.has("foo"));
      assertFalse("state should not add key with prefix: "+state.getKeys(),state.allKeys().contains(SecretFilter.SECRET_NAME_PREFIX+"foo"));
      //assertEquals("get with prefix returns value","BAR",state.get(SecretFilter.SECRET_NAME_PREFIX+"foo"));
      assertEquals("get with prefix returns value","BAR",state.get("foo"));
   }

   @Test
   public void detect_secret_in_regex(){
      Regex regex = new Regex("(?<"+SecretFilter.SECRET_NAME_PREFIX+"foo>\\d+)");
      SpyContext spyContext = new SpyContext();
      regex.run("123abc",spyContext);
      State state = spyContext.getState();
      SecretFilter secretFilter = state.getSecretFilter();

      assertEquals("exect an entry in the filter",1,secretFilter.size());
      assertTrue("expect foo in state: "+state.allKeys(),state.allKeys().contains("foo"));
   }


}
