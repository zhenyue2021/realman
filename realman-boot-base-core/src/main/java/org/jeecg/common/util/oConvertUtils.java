package org.jeecg.common.util;

import com.alibaba.fastjson.JSONArray;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.jeecg.common.constant.CommonConstant;
import org.jeecg.common.constant.SymbolConstant;
import org.jeecg.config.mybatis.MybatisPlusSaasConfig;
import org.springframework.beans.BeanUtils;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;

import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 
 * @Author  张代浩
 *
 */
@Slf4j
public class oConvertUtils {
	public static boolean isEmpty(Object object) {
		if (object == null) {
			return true;
		}
		if ("".equals(object)) {
			return true;
		}
		if (CommonConstant.STRING_NULL.equals(object)) {
			return true;
		}
		return false;
	}
	
	public static boolean isNotEmpty(Object object) {
        return object != null && !"".equals(object) && !object.equals(CommonConstant.STRING_NULL);
    }

	
	/**
	 * 返回decode解密字符串
	 * 
	 * @param inStr
	 * @return
	 */
	public static String decodeString(String inStr) {
		if (oConvertUtils.isEmpty(inStr)) {
			return null;
		}

		try {
			inStr = URLDecoder.decode(inStr, StandardCharsets.UTF_8);
		} catch (Exception e) {
			// 解决：URLDecoder: Illegal hex characters in escape (%) pattern - For input string: "自动"
			log.debug("URL解码失败，返回原始字符串: {}", inStr, e);
		}
		return inStr;
	}

	public static String decode(String strIn, String sourceCode, String targetCode) {
		String temp = code2code(strIn, sourceCode, targetCode);
		return temp;
	}

	@SuppressWarnings("AlibabaLowerCamelCaseVariableNaming")
    public static String StrToUTF(String strIn, String sourceCode, String targetCode) {
		if (strIn == null) {
			return null;
		}
		String src = StringUtils.isNotEmpty(sourceCode) ? sourceCode : StandardCharsets.ISO_8859_1.name();
		String tgt = StringUtils.isNotEmpty(targetCode) ? targetCode : "GBK";
		String out = code2code(strIn, src, tgt);
		return out != null ? out : strIn;
	}

	private static String code2code(String strIn, String sourceCode, String targetCode) {
		String strOut = null;
		if (strIn == null || strIn.trim().isEmpty()) {
			return strIn;
		}
		try {
			byte[] b = strIn.getBytes(sourceCode);
			strOut = new String(b, targetCode);
		} catch (Exception e) {
			log.error("字符串编码转换失败: sourceCode={}, targetCode={}", sourceCode, targetCode, e);
			return null;
		}
		return strOut;
	}

	public static int getInt(String s, int defval) {
		if (s == null || s.isEmpty()) {
			return defval;
		}
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return defval;
		}
	}

	public static int getInt(String s) {
		if (s == null || s.isEmpty()) {
			return 0;
		}
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	public static int getInt(String s, Integer df) {
		int defaultVal = df != null ? df : 0;
		if (s == null || s.isEmpty()) {
			return defaultVal;
		}
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return defaultVal;
		}
	}

	public static Integer[] getInts(String[] s) {
		if (s == null) {
			return null;
		}
		Integer[] integer = new Integer[s.length];
		for (int i = 0; i < s.length; i++) {
			if (s[i] == null) {
				return null;
			}
			try {
				integer[i] = Integer.parseInt(s[i].trim());
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return integer;

	}

	public static double getDouble(String s, double defval) {
		if (s == null || s.isEmpty()) {
			return defval;
		}
		try {
			return Double.parseDouble(s);
		} catch (NumberFormatException e) {
			return defval;
		}
	}

	public static double getDou(Double s, double defval) {
		if (s == null) {
			return defval;
		}
		return s;
	}

	/*public static Short getShort(String s) {
		if (StringUtil.isNotEmpty(s)) {
			return (Short.parseShort(s));
		} else {
			return null;
		}
	}*/

	public static int getInt(Object object, int defval) {
		if (isEmpty(object)) {
			return defval;
		}
		try {
			return Integer.parseInt(object.toString());
		} catch (NumberFormatException e) {
			return defval;
		}
	}
	
	public static Integer getInteger(Object object, Integer defval) {
		if (isEmpty(object)) {
			return defval;
		}
		try {
			return Integer.parseInt(object.toString());
		} catch (NumberFormatException e) {
			return defval;
		}
	}
	
	public static Integer getInt(Object object) {
		if (isEmpty(object)) {
			return null;
		}
		try {
			return Integer.parseInt(object.toString());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	public static int getInt(BigDecimal s, int defval) {
		if (s == null) {
			return defval;
		}
		return s.intValue();
	}

	public static Integer[] getIntegerArry(String[] object) {
		if (object == null) {
			return null;
		}
		int len = object.length;
		Integer[] result = new Integer[len];
		try {
			for (int i = 0; i < len; i++) {
				result[i] = Integer.valueOf(object[i].trim());
			}
			return result;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	public static String getString(String s) {
		return getString(s, "");
	}

	public static String getString(Object object) {
		if (isEmpty(object)) {
			return "";
		}
		return object.toString().trim();
	}

	public static String getString(int i) {
		return String.valueOf(i);
	}

	public static String getString(float i) {
		return String.valueOf(i);
	}


	private static final Pattern NORMAL_STRING_PATTERN = Pattern.compile("[^0-9a-zA-Z\\u4e00-\\u9fa5]");

	private static final java.security.SecureRandom SECURE_RANDOM = new java.security.SecureRandom();

	/**
	 * 返回常规字符串（只保留字符串中的数字、字母、中文）
	 *
	 * @param input
	 * @return
	 */
	public static String getNormalString(String input) {
		if (oConvertUtils.isEmpty(input)) {
			return null;
		}
		Matcher matcher = NORMAL_STRING_PATTERN.matcher(input);
		return matcher.replaceAll("");
	}

	public static String getString(String s, String defval) {
		if (isEmpty(s)) {
			return defval;
		}
		return s.trim();
	}

	public static String getString(Object s, String defval) {
		if (isEmpty(s)) {
			return defval;
		}
		return s.toString().trim();
	}

	public static long stringToLong(String str) {
		long test = 0L;
		try {
			test = Long.parseLong(str);
		} catch (Exception e) {
			log.warn("字符串转换为Long类型失败: {}", str, e);
		}
		return test;
	}

	/**
	 * 获取本机IP
	 */
	public static String getIp() {
		String ip = null;
		try {
			InetAddress address = InetAddress.getLocalHost();
			ip = address.getHostAddress();

		} catch (UnknownHostException e) {
			log.error("获取本机IP失败", e);
		}
		return ip;
	}

	/**
	 * 解码base64
	 *
	 * @param base64Str base64字符串
	 * @return 被加密后的字符串
	 */
	public static String decodeBase64Str(String base64Str) {
		if (base64Str == null) {
			return null;
		}
		byte[] byteContent = Base64.decodeBase64(base64Str);
		if (byteContent == null || byteContent.length == 0) {
			return null;
		}
		return new String(byteContent, StandardCharsets.UTF_8);
	}
	
	
	/**
	 * @param request
	 *            IP
	 * @return IP Address
	 */
	public static String getIpAddrByRequest(HttpServletRequest request) {
		String ip = request.getHeader("x-forwarded-for");
		if (ip == null || ip.isEmpty() || CommonConstant.UNKNOWN.equalsIgnoreCase(ip)) {
			ip = request.getHeader("Proxy-Client-IP");
		}
		if (ip == null || ip.isEmpty() || CommonConstant.UNKNOWN.equalsIgnoreCase(ip)) {
			ip = request.getHeader("WL-Proxy-Client-IP");
		}
		if (ip == null || ip.isEmpty() || CommonConstant.UNKNOWN.equalsIgnoreCase(ip)) {
			ip = request.getRemoteAddr();
		}
		return ip;
	}

	/**
	 * @return 本机IP
	 * @throws SocketException
	 */
	public static String getRealIp() throws SocketException {
        // 本地IP，如果没有配置外网IP则返回它
		String localip = null;
        // 外网IP
		String netip = null;

		Enumeration<NetworkInterface> netInterfaces = NetworkInterface.getNetworkInterfaces();
		InetAddress ip = null;
        // 是否找到外网IP
		boolean finded = false;
		while (netInterfaces.hasMoreElements() && !finded) {
			NetworkInterface ni = netInterfaces.nextElement();
			Enumeration<InetAddress> address = ni.getInetAddresses();
			while (address.hasMoreElements()) {
				ip = address.nextElement();
                // 外网IP
				if (!ip.isSiteLocalAddress() && !ip.isLoopbackAddress() && !ip.getHostAddress().contains(":")) {
					netip = ip.getHostAddress();
					finded = true;
					break;
				} else if (ip.isSiteLocalAddress() && !ip.isLoopbackAddress() && !ip.getHostAddress().contains(":")) {
                    // 内网IP
				    localip = ip.getHostAddress();
				}
			}
		}

		if (netip != null && !"".equals(netip)) {
			return netip;
		} else {
			return localip;
		}
	}

	/**
	 * java去除字符串中的空格、回车、换行符、制表符
	 * 
	 * @param str
	 * @return
	 */
	private static final Pattern BLANK_PATTERN = Pattern.compile("\\s*|\t|\r|\n");
	
	public static String replaceBlank(String str) {
		String dest = "";
		if (str != null) {
			Matcher m = BLANK_PATTERN.matcher(str);
			dest = m.replaceAll("");
		}
		return dest;

	}

	/**
	 * 判断元素是否在数组内
	 * 
	 * @param child
	 * @param all
	 * @return
	 */
	public static boolean isIn(String child, String[] all) {
		if (all == null || all.length == 0) {
			return false;
		}
		for (String aSource : all) {
			if (Objects.equals(child, aSource)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 判断元素是否在数组内
	 *
	 * @param childArray
	 * @param all
	 * @return
	 */
	public static boolean isArrayIn(String[] childArray, String[] all) {
		if (all == null || all.length == 0) {
			return false;
		}
		for (String v : childArray) {
			if (!isIn(v, all)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 判断元素是否在数组内
	 *
	 * @param childArray
	 * @param all
	 * @return
	 */
	public static boolean isJsonArrayIn(JSONArray childArray, String[] all) {
		if (all == null || all.length == 0) {
			return false;
		}
		if (childArray == null || childArray.isEmpty()) {
			return false;
		}

		List<String> children = childArray.toJavaList(String.class);
		for (String v : children) {
			if (!isIn(v, all)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 判断字符串是否为JSON格式
	 * @param str
	 * @return
	 */
	public static boolean isJson(String str) {
		if (str == null || str.trim().isEmpty()) {
			return false;
		}
		try {
			com.alibaba.fastjson.JSON.parse(str);
			return true;
		} catch (Exception e) {
			return false;
		}
	}
	
	/**
	 * 获取Map对象
	 */
	public static Map<Object, Object> getHashMap() {
		return new HashMap<>(5);
	}

	/**
	 * SET转换MAP
	 * 
	 * @param str
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public static Map<Object, Object> setToMap(Set<?> setobj) {
		Map<Object, Object> map = getHashMap();
		for (Object obj : setobj) {
			if (obj instanceof Map.Entry) {
				Map.Entry<Object, Object> entry = (Map.Entry<Object, Object>) obj;
				map.put(entry.getKey().toString(), entry.getValue() == null ? "" : entry.getValue().toString().trim());
			}
		}
		return map;
	}

	private static boolean isInner(long userIp, long begin, long end) {
		return (userIp >= begin) && (userIp <= end);
	}
	
	/**
	 * 将下划线大写方式命名的字符串转换为驼峰式。
	 * 如果转换前的下划线大写方式命名的字符串为空，则返回空字符串。</br>
	 * 例如：hello_world->helloWorld
	 * 
	 * @param name
	 *            转换前的下划线大写方式命名的字符串
	 * @return 转换后的驼峰式命名的字符串
	 */
	public static String camelName(String name) {
		StringBuilder result = new StringBuilder();
		// 快速检查
		if (name == null || name.isEmpty()) {
			// 没必要转换
			return "";
		} else if (!name.contains(SymbolConstant.UNDERLINE)) {
			// 不含下划线，仅将首字母小写
			// 代码逻辑说明: TASK #2500 【代码生成器】代码生成器开发一通用模板生成功能
			return name.substring(0, 1).toLowerCase() + name.substring(1).toLowerCase();
		}
		// 用下划线将原始字符串分割
		String[] camels = name.split("_");
		for (String camel : camels) {
			// 跳过原始字符串中开头、结尾的下换线或双重下划线
			if (camel.isEmpty()) {
				continue;
			}
			// 处理真正的驼峰片段
			if (result.isEmpty()) {
				// 第一个驼峰片段，全部字母都小写
				result.append(camel.toLowerCase());
			} else {
				// 其他的驼峰片段，首字母大写
				result.append(Character.toUpperCase(camel.charAt(0)));
				result.append(camel.substring(1).toLowerCase());
			}
		}
		return result.toString();
	}
	
	/**
	 * 将下划线大写方式命名的字符串转换为驼峰式。
	 * 如果转换前的下划线大写方式命名的字符串为空，则返回空字符串。</br>
	 * 例如：hello_world,test_id->helloWorld,testId
	 * 
	 * @param name
	 *            转换前的下划线大写方式命名的字符串
	 * @return 转换后的驼峰式命名的字符串
	 */
	public static String camelNames(String names) {
		if(names==null|| names.isEmpty()){
			return null;
		}
		StringBuilder sf = new StringBuilder();
		String[] fs = names.split(",");
		for (String field : fs) {
			field = camelName(field);
			sf.append(field).append(",");
		}
		String result = sf.toString();
		return result.substring(0, result.length() - 1);
	}
	
	/**
	 * 将下划线大写方式命名的字符串转换为驼峰式。(首字母写)
	 * 如果转换前的下划线大写方式命名的字符串为空，则返回空字符串。</br>
	 * 例如：hello_world->HelloWorld
	 * 
	 * @param name
	 *            转换前的下划线大写方式命名的字符串
	 * @return 转换后的驼峰式命名的字符串
	 */
	public static String camelNameCapFirst(String name) {
		StringBuilder result = new StringBuilder();
		// 快速检查
		if (name == null || name.isEmpty()) {
			// 没必要转换
			return "";
		} else if (!name.contains(SymbolConstant.UNDERLINE)) {
			// 不含下划线，仅将首字母小写
			return Character.toUpperCase(name.charAt(0)) + name.substring(1).toLowerCase();
		}
		// 用下划线将原始字符串分割
		String[] camels = name.split("_");
		for (String camel : camels) {
			// 跳过原始字符串中开头、结尾的下换线或双重下划线
			if (camel.isEmpty()) {
				continue;
			}
			// 其他的驼峰片段，首字母大写
			result.append(Character.toUpperCase(camel.charAt(0)));
			result.append(camel.substring(1).toLowerCase());
		}
		return result.toString();
	}
	
	/**
	 * 将驼峰命名转化成下划线
	 * @param para
	 * @return
	 */
	public static String camelToUnderline(String para){
		if (para == null || para.isEmpty()) {
			return para;
		}
	    int length = 3;
        if(para.length()<length){
        	return para.toLowerCase(); 
        }
        StringBuilder sb=new StringBuilder(para);
        //定位
        int temp=0;
        //从第三个字符开始 避免命名不规范 
        for(int i=2;i<para.length();i++){
            if(Character.isUpperCase(para.charAt(i))){
                sb.insert(i+temp, "_");
                temp+=1;
            }
        }
        return sb.toString().toLowerCase(); 
	}

	/**
	 * 随机数
	 * @param place 定义随机数的位数
	 */
	public static String randomGen(int place) {
		if (place <= 0) {
			return "";
		}
		String base = "qwertyuioplkjhgfdsazxcvbnmQAZWSXEDCRFVTGBYHNUJMIKLOP0123456789";
		StringBuilder sb = new StringBuilder(place);
		for (int i = 0; i < place; i++) {
			sb.append(base.charAt(SECURE_RANDOM.nextInt(base.length())));
		}
		return sb.toString();
	}
	
	/**
	 * 获取类的所有属性，包括父类
	 * 
	 * @param object
	 * @return
	 */
	public static Field[] getAllFields(Object object) {
		Class<?> clazz = object.getClass();
		List<Field> fieldList = new ArrayList<>();
		while (clazz != null) {
			fieldList.addAll(Arrays.asList(clazz.getDeclaredFields()));
			clazz = clazz.getSuperclass();
		}
		Field[] fields = new Field[fieldList.size()];
		fieldList.toArray(fields);
		return fields;
	}
	
	/**
	  * 将map的key全部转成小写
	 * @param list
	 * @return
	 */
	public static List<Map<String, Object>> toLowerCasePageList(List<Map<String, Object>> list){
		List<Map<String, Object>> select = new ArrayList<>();
		for (Map<String, Object> row : list) {
			 Map<String, Object> resultMap = new HashMap<>(5);
			 Set<String> keySet = row.keySet(); 
			 for (String key : keySet) { 
				 String newKey = key.toLowerCase(); 
				 resultMap.put(newKey, row.get(key)); 
			 }
			 select.add(resultMap);
		}
		return select;
	}

	/**
	 * 将entityList转换成modelList
	 * @param fromList
	 * @param tClass
	 * @param <F>
	 * @param <T>
	 * @return
	 */
	public static<F,T> List<T> entityListToModelList(List<F> fromList, Class<T> tClass){
		if(fromList == null || fromList.isEmpty()){
			return null;
		}
		List<T> tList = new ArrayList<>();
		for(F f : fromList){
			T t = entityToModel(f, tClass);
			tList.add(t);
		}
		return tList;
	}

	public static<F,T> T entityToModel(F entity, Class<T> modelClass) {
		log.debug("entityToModel : Entity属性的值赋值到Model");
		Object model = null;
		if (entity == null || modelClass ==null) {
			return null;
		}

		try {
			model = modelClass.getDeclaredConstructor().newInstance();
		} catch (ReflectiveOperationException e) {
			log.error("entityToModel : 实例化异常", e);
		}
		if (model == null) {
			return null;
		}
		BeanUtils.copyProperties(entity, model);
		return (T)model;
	}

	/**
	 * 判断 list 是否为空
	 *
	 * @param list
	 * @return true or false
	 * list == null		: true
	 * list.size() == 0	: true
	 */
	public static boolean listIsEmpty(Collection<?> list) {
		return list == null || list.isEmpty();
	}

	/**
	 * 判断旧值与新值 是否相等
	 *
	 * @param oldVal
	 * @param newVal
	 * @return
	 */
	public static boolean isEqual(Object oldVal, Object newVal) {
		if (oldVal == null && newVal == null) {
			return true;
		}
		if (oldVal == null || newVal == null) {
			return false;
		}
		// 两个都不为null
		if (isArray(oldVal)) {
			return equalityOfArrays((Object[]) oldVal, (Object[]) newVal);
		} else if(oldVal instanceof JSONArray){
			if(newVal instanceof JSONArray){
				return equalityOfJSONArray((JSONArray) oldVal, (JSONArray) newVal);
			}else{
				if (isEmpty(newVal) && ((JSONArray) oldVal).size() == 0) {
					return true;
				}
				String[] strArray = newVal.toString().split(",");
				List<String> arrayStr = Arrays.asList(strArray);
				JSONArray newValArray = new JSONArray(arrayStr);
				return equalityOfJSONArray((JSONArray) oldVal, newValArray);
			}
		} else {
			return oldVal.equals(newVal);
		}
	}

	/**
	 * 方法描述 判断一个对象是否是一个数组
	 *
	 * @param obj
	 * @return
	 * @author yaomy
	 * @date 2018年2月5日 下午5:03:00
	 */
	public static boolean isArray(Object obj) {
		if (obj == null) {
			return false;
		}
		return obj.getClass().isArray();
	}

	/**
	 * 获取集合的大小
	 * 
	 * @param collection
	 * @return
	 */
	public static int getCollectionSize(Collection<?> collection) {
		return collection != null ? collection.size() : 0;
	}
	
	/**
	 * 判断两个数组是否相等（数组元素不分顺序）
	 *
	 * @param oldVal
	 * @param newVal
	 * @return
	 */
	public static boolean equalityOfJSONArray(JSONArray oldVal, JSONArray newVal) {
		if (oldVal != null && newVal != null) {
			Object[] oldValArray = oldVal.toArray();
			Object[] newValArray = newVal.toArray();
			return equalityOfArrays(oldValArray,newValArray);
		} else {
			if ((oldVal == null || oldVal.size() == 0) && (newVal == null || newVal.size() == 0)) {
				return true;
			} else {
				return false;
			}
		}
	}

	/**
	 * 比较带逗号的字符串
	 * QQYUN-5212【简流】按日期触发 多选 人员组件 选择顺序不一致时 不触发，应该是统一问题 包括多选部门组件
	 * @param oldVal
	 * @param newVal
	 * @return
	 */
	public static boolean equalityOfStringArrays(String oldVal, String newVal) {
		if (oldVal == null && newVal == null) {
			return true;
		}
		if (oldVal == null || newVal == null) {
			return false;
		}
		if (oldVal.equals(newVal)) {
			return true;
		}
		if(oldVal.contains(",") && newVal.contains(",")){
			String[] arr1 = oldVal.split(",");
			String[] arr2 = newVal.split(",");
			if(arr1.length == arr2.length){
				boolean flag = true;
				Map<String, Integer> map = new HashMap<>();
				for(String s1: arr1){
					map.put(s1, 1);
				}
				for(String s2: arr2){
					if(map.get(s2) == null){
						flag = false;
						break;
					}
				}
				return flag;
			}
		}
		return false;
	}
	
	/**
	 * 判断两个数组是否相等（数组元素不分顺序）
	 *
	 * @param oldVal
	 * @param newVal
	 * @return
	 */
	public static boolean equalityOfArrays(Object[] oldVal, Object newVal[]) {
		if (oldVal != null && newVal != null) {
			if (oldVal.length != newVal.length) {
				return false;
			}
			// 使用Set比较，避免修改原数组且提高性能
			Set<Object> oldSet = new HashSet<>(Arrays.asList(oldVal));
			Set<Object> newSet = new HashSet<>(Arrays.asList(newVal));
			return oldSet.equals(newSet);
		} else {
			if ((oldVal == null || oldVal.length == 0) && (newVal == null || newVal.length == 0)) {
				return true;
			} else {
				return false;
			}
		}
	}


	/**
	 * 判断 list 是否不为空
	 *
	 * @param list
	 * @return true or false
	 * list == null		: false
	 * list.size() == 0	: false
	 */
	public static boolean listIsNotEmpty(Collection<?> list) {
		return !listIsEmpty(list);
	}

	/**
	 * 读取静态文本内容
	 * @param url
	 * @return
	 */
	public static String readStatic(String url) {
		if (url == null) {
			return "";
		}
		String path = url.replace("classpath:", "");
		try (InputStream stream = oConvertUtils.class.getClassLoader().getResourceAsStream(path)) {
			if (stream == null) {
				log.warn("Classpath resource not found: {}", path);
				return "";
			}
			return IOUtils.toString(stream, StandardCharsets.UTF_8);
		} catch (IOException e) {
			log.error("readStatic failed: {}", path, e);
			return "";
		}
	}

	/**
	 * 将List 转成 JSONArray
	 * @return
	 */
	public static JSONArray list2JSONArray(List<String> list){
		if(list==null || list.size()==0){
			return null;
		}
		JSONArray array = new JSONArray();
        array.addAll(list);
		return array;
	}

	/**
	 * 判断两个list中的元素是否完全一致
	 * QQYUN-5326【简流】获取组织人员 单/多 筛选条件 没有部门筛选
	 * @return
	 */
	public static boolean isEqList(List<String> list1, List<String> list2){
		if (list1 == null && list2 == null) {
			return true;
		}
		if (list1 == null || list2 == null) {
			return false;
		}
		if (list1.size() != list2.size()) {
			return false;
		}
		// 使用Set提高比较效率，从O(n²)优化到O(n)
		Set<String> set1 = new HashSet<>(list1);
		Set<String> set2 = new HashSet<>(list2);
		return set1.equals(set2);
	}


	/**
	 * 判断 sourceList中的元素是否在targetList中出现
	 * 
	 * QQYUN-5326【简流】获取组织人员 单/多 筛选条件 没有部门筛选
	 * @param sourceList 源列表，要检查的元素列表
	 * @param targetList 目标列表，用于匹配的列表
	 * @return 如果sourceList中有任何元素在targetList中存在则返回true，否则返回false
	 */
	public static boolean isInList(List<String> sourceList, List<String> targetList){
		if (sourceList == null || sourceList.isEmpty() || targetList == null || targetList.isEmpty()) {
			return false;
		}
		// 使用HashSet提高查找效率，从O(n*m)优化到O(n+m)
		Set<String> targetSet = new HashSet<>(targetList);
		for(String sourceItem: sourceList){
			if (sourceItem != null && targetSet.contains(sourceItem)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 判断 sourceList中的所有元素是否都在targetList中存在
	 * @param sourceList 源列表，要检查的元素列表
	 * @param targetList 目标列表，用于匹配的列表
	 * @return 如果sourceList中的所有元素都在targetList中存在则返回true，否则返回false
	 */
	public static boolean isAllInList(List<String> sourceList, List<String> targetList){
		if(sourceList == null || sourceList.isEmpty()){
			return true; // 空列表视为所有元素都存在
		}
		if(targetList == null || targetList.isEmpty()){
			return false; // 目标列表为空，源列表非空时返回false
		}

		// 使用HashSet提高查找效率，从O(n*m)优化到O(n+m)
		Set<String> targetSet = new HashSet<>(targetList);
		for(String sourceItem: sourceList){
			if (sourceItem == null || !targetSet.contains(sourceItem)) {
				return false; // 有任何一个元素不在目标列表中，返回false
			}
		}
		return true; // 所有元素都找到了
	}
	
	/**
	 * 计算文件大小转成MB
	 * @param uploadCount
	 * @return
	 */
	private static final BigDecimal BYTES_PER_MB = new BigDecimal(1048576);
	private static final long PRIVATE_IP_A_BEGIN = getIpNumStatic("10.0.0.0");
	private static final long PRIVATE_IP_A_END = getIpNumStatic("10.255.255.255");
	private static final long PRIVATE_IP_B_BEGIN = getIpNumStatic("172.16.0.0");
	private static final long PRIVATE_IP_B_END = getIpNumStatic("172.31.255.255");
	private static final long PRIVATE_IP_C_BEGIN = getIpNumStatic("192.168.0.0");
	private static final long PRIVATE_IP_C_END = getIpNumStatic("192.168.255.255");
	private static final String LOCAL_IP = "127.0.0.1";
	
	private static long getIpNumStatic(String ipAddress) {
		String[] ip = ipAddress.split("\\.");
		long a = Integer.parseInt(ip[0]);
		long b = Integer.parseInt(ip[1]);
		long c = Integer.parseInt(ip[2]);
		long d = Integer.parseInt(ip[3]);
		return a * 256 * 256 * 256 + b * 256 * 256 + c * 256 + d;
	}
	
	public static boolean isInnerIp(String ipAddress) {
		if (ipAddress == null || ipAddress.trim().isEmpty()) {
			return false;
		}
		long ipNum = getIpNum(ipAddress);
		return isInner(ipNum, PRIVATE_IP_A_BEGIN, PRIVATE_IP_A_END) 
			|| isInner(ipNum, PRIVATE_IP_B_BEGIN, PRIVATE_IP_B_END) 
			|| isInner(ipNum, PRIVATE_IP_C_BEGIN, PRIVATE_IP_C_END) 
			|| LOCAL_IP.equals(ipAddress);
	}

	private static long getIpNum(String ipAddress) {
		if (ipAddress == null) {
			throw new IllegalArgumentException("IP地址不能为空");
		}
		String[] ip = ipAddress.split("\\.");
		if (ip.length != 4) {
			throw new IllegalArgumentException("IP地址格式错误: " + ipAddress);
		}
		try {
			long a = Integer.parseInt(ip[0]);
			long b = Integer.parseInt(ip[1]);
			long c = Integer.parseInt(ip[2]);
			long d = Integer.parseInt(ip[3]);
			return a * 256 * 256 * 256 + b * 256 * 256 + c * 256 + d;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("IP地址格式错误: " + ipAddress, e);
		}
	}
	
	/**
	 * 计算文件大小转成MB
	 * @param uploadCount
	 * @return
	 */
	public static Double calculateFileSizeToMb(Long uploadCount){
		if(uploadCount == null || uploadCount <= 0) {
			return 0.0;
		}
		BigDecimal bigDecimal = new BigDecimal(uploadCount);
		//换算成MB
		BigDecimal divide = bigDecimal.divide(BYTES_PER_MB, 10, RoundingMode.HALF_UP);
		return divide.setScale(2, RoundingMode.HALF_UP).doubleValue();
	}

	/**
	 * map转str
	 *
	 * @param map
	 * @return
	 */
	public static String mapToString(Map<String, String[]> map) {
		if (map == null || map.isEmpty()) {
			return null;
		}

		StringBuilder sb = new StringBuilder();
		boolean first = true;
		for (Map.Entry<String, String[]> entry : map.entrySet()) {
			if (!first) {
				sb.append("&");
			}
			String key = entry.getKey();
			String[] values = entry.getValue();
			sb.append(key).append("=");
			sb.append(values != null ? StringUtils.join(values, ",") : "");
			first = false;
		}

		return sb.toString();
	}

	/**
	 * 判断对象是否为空 <br/>
	 * 支持各种类型的对象
	 * for for [QQYUN-10990]AIRAG
	 * @param obj
	 * @return
	 * @author chenrui
	 * @date 2025/2/13 18:34
	 */
	public static boolean isObjectEmpty(Object obj) {
		if (null == obj) {
			return true;
		}

		if (obj instanceof CharSequence) {
			return isEmpty(obj);
		} else if (obj instanceof Map) {
			return ((Map<?, ?>) obj).isEmpty();
		} else if (obj instanceof Iterable) {
			return isObjectEmpty(((Iterable<?>) obj).iterator());
		} else if (obj instanceof Iterator) {
			return !((Iterator<?>) obj).hasNext();
		} else if (isArray(obj)) {
			return 0 == Array.getLength(obj);
		}
		return false;
	}

	/**
	 * iterator 是否为空
	 * for for [QQYUN-10990]AIRAG
	 * @param iterator Iterator对象
	 * @return 是否为空
	 */
	public static boolean isEmptyIterator(Iterator<?> iterator) {
		return null == iterator || !iterator.hasNext();
	}


	/**
	 * 判断对象是否不为空
	 * for for [QQYUN-10990]AIRAG
	 * @param object
	 * @return
	 * @author chenrui
	 * @date 2025/2/13 18:35
	 */
	public static boolean isObjectNotEmpty(Object object) {
		return !isObjectEmpty(object);
	}

	/**
	 * 如果src大于des返回true
	 * for [QQYUN-10990]AIRAG
	 * @param src
	 * @param des
	 * @return
	 * @author: chenrui
	 * @date: 2018/9/19 15:30
	 */
	public static boolean isGt(Number src, Number des) {
		if (null == src || null == des) {
			throw new IllegalArgumentException("参数不能为空");
		}
		return src.doubleValue() > des.doubleValue();
	}

	/**
	 * 如果src大于等于des返回true
	 * for [QQYUN-10990]AIRAG
	 * @param src
	 * @param des
	 * @return
	 * @author: chenrui
	 * @date: 2018/9/19 15:30
	 */
	public static boolean isGe(Number src, Number des) {
		if (null == src || null == des) {
			throw new IllegalArgumentException("参数不能为空");
		}
		return src.doubleValue() >= des.doubleValue();
	}


	/**
	 * 判断是否存在
	 * for [QQYUN-10990]AIRAG
	 * @param obj
	 * @param objs
	 * @param <T>
	 * @return
	 * @author chenrui
	 * @date 2020/9/12 15:50
	 */
	public static <T> boolean isIn(T obj, T... objs) {
		if (isEmpty(objs)) {
			return false;
		}
		for (T obj1 : objs) {
			if (isEqual(obj, obj1)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 判断租户ID是否有效
	 * @param tenantId
	 * @return
	 */
	public static boolean isEffectiveTenant(String tenantId) {
		return Boolean.TRUE.equals(MybatisPlusSaasConfig.OPEN_SYSTEM_TENANT_CONTROL)
				&& isNotEmpty(tenantId) && !"0".equals(tenantId);
	}

    /**
     * 复制源对象的非空属性到目标对象（同名属性）
     * 
     * @param source 源对象（页面）
     * @param target 目标对象（数据库实体）
     */
    public static void copyNonNullFields(Object source, Object target) {
        if (source == null || target == null) {
            return;
        }
        // 获取源对象的非空属性名数组
        String[] nullPropertyNames = getNullPropertyNames(source);
        // 复制：忽略源对象的空属性，仅覆盖目标对象的对应非空属性
        BeanUtils.copyProperties(source, target, nullPropertyNames);
    }

    /**
     * 获取源对象中值为 null 的属性名数组
     * 
     * @param source 
     */
    private static String[] getNullPropertyNames(Object source) {
        BeanWrapper beanWrapper = new BeanWrapperImpl(source);
        //获取类的属性
        PropertyDescriptor[] propertyDescriptors = beanWrapper.getPropertyDescriptors();
        // 过滤出值为 null 的属性名
        return Stream.of(propertyDescriptors)
                .map(PropertyDescriptor::getName)
                .filter(name -> beanWrapper.getPropertyValue(name) == null)
                .toArray(String[]::new);
    }

    /**
     * String转换long类型
     *
     * @param v
     * @param def
     * @return
     */
    public static long getLong(Object v, long def) {
        if (v == null) {
            return def;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (Exception e) {
            return def;
        }
    }
	
}
