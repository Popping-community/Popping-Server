## 스프링 MVC

### DispatcherServlet

스프링 MVC의 프론트 컨트롤러가 바로 디스패처 서블릿(DispatcherServlet)이다.

서블릿이 호출되면 HttpServlet이 제공하는 service()가 호출되면서 DispatcherServlet.doDispatch()가 호출된다.

```java
 protected void doDispatch(HttpServletRequest request, HttpServletResponse 
response) throws Exception {
 HttpServletRequest processedRequest = request;
 HandlerExecutionChain mappedHandler = null;
 ModelAndView mv = null;
 // 1. 핸들러 조회
mappedHandler = getHandler(processedRequest);
 if (mappedHandler == null) {
 noHandlerFound(processedRequest, response);
 return;
 }
 // 2. 핸들러 어댑터 조회 - 핸들러를 처리할 수 있는 어댑터
HandlerAdapter ha = getHandlerAdapter(mappedHandler.getHandler());
 // 3. 핸들러 어댑터 실행 -> 4. 핸들러 어댑터를 통해 핸들러 실행 -> 5. ModelAndView 반환
mv = ha.handle(processedRequest, response, mappedHandler.getHandler());
 processDispatchResult(processedRequest, response, mappedHandler, mv, 
dispatchException);
 }
 private void processDispatchResult(HttpServletRequest request, 
HttpServletResponse response, HandlerExecutionChain mappedHandler, ModelAndView 
mv, Exception exception) throws Exception {
// 뷰 렌더링 호출
render(mv, request, response);
 }
 protected void render(ModelAndView mv, HttpServletRequest request, 
HttpServletResponse response) throws Exception {
 View view;
 String viewName = mv.getViewName();
 // 6. 뷰 리졸버를 통해서 뷰 찾기, 7. View 반환
view = resolveViewName(viewName, mv.getModelInternal(), locale, request);
 // 8. 뷰 렌더링
view.render(mv.getModelInternal(), request, response);
 }
```

#### 동작 순서

1. 핸들러 조회
2. 핸들러 어댑터 조회
3. 핸들러 어댑터 실행
4. 핸들러 실행
5. ModelAndView 반환
6. viewResolver 호출
   * JSP의 경우: ` InternalResourceViewResolver` 가 자동 등록되고, 사용된다.
7. View 반환
   *  JSP의 경우 ` InternalResourceView(JstlView)` 를 반환하는데, 내부에 ` forward()` 로직이 있다
8. 뷰 렌더링

##### 스프링 MVC의 큰 강점은 DispatcherServlet 코드의 변경없이, 원하는 기능을 변경하거나 확장할 수 있다는 점이다. 인터페이스로 제공하기 때문이다.

### 핸들러 매핑과 핸들러 어댑터

과거 버전 스프링 컨트롤러

org.springframework.web.servlet.mvc.Controller

```java
@Component("/springmvc/old-controller")
 public class OldController implements Controller {
    @Override
 public ModelAndView handleRequest(HttpServletRequest request, 
HttpServletResponse response) throws Exception {
 return null;
    }
 }
```

s
