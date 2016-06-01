package org.ystyle.servlet;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;
import org.ystyle.Container.BeanContainer;
import org.ystyle.action.ContextAction;
import org.ystyle.converter.TypeConverter;
import org.ystyle.servlet.util.ActionVo;
import org.ystyle.servlet.util.ControlXML;
import org.ystyle.servlet.util.Result;
import org.ystyle.utils.ActionContext;
import org.ystyle.utils.InvocakeHelp;
import org.ystyle.utils.Utils;

public class MainServlet extends HttpServlet {

	private ServletContext servletContext;
	private String encoding;

	private static final Logger logger = Logger.getLogger(MainServlet.class);

	public MainServlet() {
		super();
	}

	public void service(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {


		try {
			// 字符编码
			request.setCharacterEncoding(encoding);
			response.setCharacterEncoding(encoding);

			logger.debug("-------主控制servlet---------");
			String requestURI = request.getRequestURI();
			logger.debug("requestURI: " + requestURI);
			if (requestURI.endsWith("/")) {
				requestURI = requestURI.substring(0, requestURI.length() - 1);
			}
			requestURI = requestURI.replaceFirst(request.getContextPath(), "");
			logger.debug("requestURI: " + requestURI);
			String namespace = requestURI.substring(0, requestURI
					.lastIndexOf("/"));
			String actionname = requestURI.substring(requestURI
					.lastIndexOf("/") + 1, requestURI.lastIndexOf("."));
			logger.debug("namespace:" + namespace);
			logger.debug("actionname:" + actionname);

			// 注入Action Context
			Map<String, Object> contextMap = new HashMap<String, Object>();
			contextMap.put(ActionContext.HTTPREQUEST, request);
			contextMap.put(ActionContext.HTTPRESPONSE, response);
			ActionContext.setContext(new ActionContext(contextMap));

			ControlXML controlXml = ControlXML.getInstance();
			ActionVo avo = controlXml.getAction(namespace, actionname);

			if (avo == null) {
				// 没找到配置的action，那么直接转发到jsp
				request.getRequestDispatcher(
						"/WEB-INF" + namespace + "/" + actionname + ".jsp")
						.forward(request, response);
				return;
			}
			logger.debug("avo.getName():" + avo.getName());
			logger.debug("avo.getMethod():" + avo.getMethod());
			logger.debug("avo.getClassName():" + avo.getClassName());

			Object action = InvocakeHelp.newInstance(avo.getClassName(), null);

			if (action == null) {
				throw new RuntimeException("无法实例化action" + avo.getClassName());
			}

//			Map<String, String[]> paramMap = new HashMap<String, String[]>();// request.getParameterMap();
			String contentType = request.getContentType();

			// 假如是带有上传，那么利用common fileupload封装表单
			if (contentType != null
					&& contentType.startsWith("multipart/form-data")) {
				request = new MulRequestWraper(request);
				// 重新注入新的request
				ActionContext.getContext().put(ActionContext.HTTPREQUEST,
						request);
			}

			Map<String, String[]> paramMap = request.getParameterMap();

			
			String client_token = request.getParameter("client_token");
			if (!Utils.isEmpty(client_token)) {
				String token = (String) request.getSession().getAttribute(
						"token");
				if (!client_token.equals(token)) {
					// 重复提交
					
					Result result = avo.getResults().get("invalid.token");
					if(result==null){
						result = avo.getResults().get("success");
					}
					if (result != null) {
						if ("redirect".equals(result.getType())) {
							response.sendRedirect(request.getContextPath()
									+ "/" + result.getUrltext());
						} else {
							request.getRequestDispatcher(
									"/" + result.getUrltext()).forward(request,
									response);
						}
						return ;
						
					} 

				} else {
					// 正常提交
					request.getSession().setAttribute("token", "");
				}
			}
			
			

			// 为action注入所需要的元素 比如context request reponse等
			setActionContext(action, request, response);

			Set<Entry<String, String[]>> paramSet = paramMap.entrySet();
			Map<String, Object> fieldMap = new HashMap<String, Object>();
			try {
				for (Entry<String, String[]> ent : paramSet) {
					String paramName = (String) ent.getKey();
					Object[] paramValue = ent.getValue();

					handField(fieldMap, paramName, paramValue, action);

				}
			} catch (Exception ex) {
				ex.printStackTrace();
			}
			/*
			 * 将action中的javabean封装
			 */
			Set<String> javabeanKeys = fieldMap.keySet();
			for (Iterator<String> it = javabeanKeys.iterator(); it.hasNext();) {
				String javabeanKey = it.next();
				String javabeanSetMethod = "set"
						+ javabeanKey.substring(0, 1).toUpperCase()
						+ javabeanKey.substring(1);
				InvocakeHelp.invokeMethod(action, javabeanSetMethod,
						new Object[] { fieldMap.get(javabeanKey) });
				fieldMap.get(javabeanKey);
			}

			/*
			 * 将action所有用到@Autowired这个注解的域进行解析并装配注入
			 */
			// AnnotationHelp.AutowiredSet(action);
			BeanContainer.instince().AutowiredSet(action);
			logger.debug(BeanContainer.instince().getMapInfo());
			Object actionValue = InvocakeHelp.invokeMethod(action, avo
					.getMethod(), null);

			if (actionValue == null || actionValue.equals("")) {
				return;
			}
			Result result = controlXml.getGlobalResults().get(actionValue);
			if (result == null) {
				result = avo.getResults().get(actionValue);
			}

			if (result != null) {
				if ("redirect".equals(result.getType())) {
					response.sendRedirect(request.getContextPath() + "/"
							+ result.getUrltext());
				} else {
					request.getRequestDispatcher("/" + result.getUrltext())
							.forward(request, response);
				}
			}
		} finally {
			// 释放上下文资源
			ActionContext.setContext(null);
		}

	}

	private void handField(Map<String, Object> fieldMap, String paramName,
			Object[] paramValue, Object action) throws Exception {
		String[] paramVos = paramName.split("\\.");

		if (paramVos.length == 2) {
			Class actionClass = action.getClass();
			Object fieldObj = fieldMap.get(paramVos[0]);
			Field field = actionClass.getDeclaredField(paramVos[0]);
			;
			if (fieldObj == null) {
				Class fieldClass = field.getType();
				fieldObj = fieldClass.newInstance();
				fieldMap.put(paramVos[0], fieldObj);
			}
			String setMethod = "set"
					+ paramVos[1].substring(0, 1).toUpperCase()
					+ paramVos[1].substring(1);
			Field fieldField = null;
			fieldField = fieldObj.getClass().getDeclaredField(paramVos[1]);

			// 执行set方法
			Map<String, TypeConverter> convertMap = ControlXML.getInstance()
					.getConvertMap();

			// 得到被反射的属性的类别名称，假如是数组，也返回原始类型
			String fieldTypeClassName = (fieldField.getType().isArray() ? fieldField
					.getType().getComponentType().getName()
					: fieldField.getType().getName());

			Object realValue = paramValue;

			/*
			 * 假如传递过来的是字符串数组（比如多选）并且被注入的元素类别是非数组 那么会被以逗号分割拼接，假如是数组就不用处理，直接用数组接收
			 */
			if (paramValue instanceof String[]) {
				if (!fieldField.getType().isArray()) {
					realValue = Utils.join((String[]) paramValue, ",");
				}
			}

			if (realValue != null && convertMap.containsKey(fieldTypeClassName)) {
				realValue = convertMap.get(fieldTypeClassName).convertValue(
						realValue, fieldField);
			}
			if (realValue != null) {
				InvocakeHelp.invokeMethod(fieldObj, setMethod,
						new Object[] { realValue });
			}

		}
	}

	/**
	 * 为action注入请求相关等的资源
	 * 
	 * @param action
	 *            请求action
	 * @param request
	 *            当前请求
	 * @param response
	 *            当前响应
	 */
	private void setActionContext(Object action, HttpServletRequest request,
			HttpServletResponse response) {
		if (!(action instanceof ContextAction)) {
			throw new RuntimeException("当前版本需要实现ContextAction接口");
		}
		ContextAction ca = (ContextAction) action;
		ca.setRequest(request);
		ca.setResponse(response);
		ca.setSession(request.getSession());
		ca.setServletContext(servletContext);

	}

	public void init(ServletConfig sc) throws ServletException {
		super.init();
		servletContext = sc.getServletContext();
		ControlXML controlXml = ControlXML.getInstance();
		String control_config = sc.getInitParameter("CONTROL_CONFIG");

		if (control_config == null || control_config.trim().equals("")) {
			control_config = Thread.currentThread().getContextClassLoader()
					.getResource(ControlXML.CONTROL_CONFIG).getFile();
		} else if (control_config.startsWith("classpath:")) {
			control_config = control_config.split(":")[1];
			control_config = Thread.currentThread().getContextClassLoader()
					.getResource(control_config).getFile();
		} else {
			control_config = sc.getServletContext().getRealPath("WEB-INF")
					+ File.separator + control_config;
		}

		try {
			controlXml.readXml(control_config);
		} catch (Exception e) {
			e.printStackTrace();
		}

		// 处理字符编码
		String encoding_p = sc.getInitParameter("ENCODING");
		this.encoding = encoding_p;
	}

	public void destroy() {
		super.destroy();
	}
}
