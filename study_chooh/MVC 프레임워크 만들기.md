## MVC 프레임워크 만들기

### FrontController 패턴

* 프론트 컨트롤러 서블릿 하나로 클라이언트의 요청을 받음
* 프론트 컨트롤러가 요청에 맞는 컨트롤러를 찾아서 호출
* 공통 처리 가능
* 나머지 컨트롤러는 서블릿을 사용하지 않아도 됨

스프링 웹 MVC의 핵심도 바로 FrontController

스프링 웹 MVC의 DispatcherServlet이 FrontController 패턴으로 구현되어 있음

### FrontControllerServletV1

```java
 public interface ControllerV1 {
 void process(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException;
 }
```

서블릿과 비슷한 모양의 컨트롤러 인터페이스를 도입한다.

```java
public class MemberFormControllerV1 implements ControllerV1 {
    @Override
 public void process(HttpServletRequest request, HttpServletResponse 
response) throws ServletException, IOException {
 String viewPath = "/WEB-INF/views/new-form.jsp";
 RequestDispatcher dispatcher = request.getRequestDispatcher(viewPath);
        dispatcher.forward(request, response);
    }
 }
```

각 컨트롤러는 인터페이스를 구현한다.

```java
 @WebServlet(name = "frontControllerServletV1", urlPatterns = "/front-controller/v1/*")
 public class FrontControllerServletV1 extends HttpServlet {
 private Map<String, ControllerV1> controllerMap = new HashMap<>();
 public FrontControllerServletV1() {
        controllerMap.put("/front-controller/v1/members/new-form", new 
MemberFormControllerV1());
        controllerMap.put("/front-controller/v1/members/save", new 
MemberSaveControllerV1());
        controllerMap.put("/front-controller/v1/members", new 
MemberListControllerV1());
    }
    @Override
 protected void service(HttpServletRequest request, HttpServletResponse response)
 throws ServletException, IOException {
 System.out.println("FrontControllerServletV1.service");
 String requestURI = request.getRequestURI();
ControllerV1 controller = controllerMap.get(requestURI);
 if (controller == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
 return;
        }
        controller.process(request, response);
    }
 }
```

프론트 컨트롤러는 인터페이스를 호출해서 구현과 관계없이 로직의 일관성을 가져갈 수 있다.

매핑해놓은 controllerMap을 활용해서 해당 경로의 controller을 실행할 수 있다.

###  FrontControllerServletV2

```java
 public class MyView {
 private String viewPath;
 public MyView(String viewPath) {
 this.viewPath = viewPath;
    }
 public void render(HttpServletRequest request, HttpServletResponse response) 
throws ServletException, IOException {
 RequestDispatcher dispatcher = request.getRequestDispatcher(viewPath);
        dispatcher.forward(request, response);
    }
 }
```

뷰로 렌더링하는 부분을 공통으로 처리할 수 있게 class를 만들었다.

```java
public class MemberFormControllerV2 implements ControllerV2 {
    @Override
 public MyView process(HttpServletRequest request, HttpServletResponse 
response) throws ServletException, IOException {
 return new MyView("/WEB-INF/views/new-form.jsp");
    }
 }
```

MyView객체를 생성하고 거기에 뷰 이름만 넣고 반환하면

```java
MyView view = controller.process(request, response);
view.render(request, response);
```

FrontController에서 알맞은 jsp를 실행시킬 수 있다.

### Model 추가 - v3

#### 서블릿 종속성 제거

요청 파라미터 정보는 자바의 Map으로 대신 넘기도록 하면 지금 구조에서는 컨트롤러가 서블릿 기술을 몰라도 동작할 수 있다.

그리고 request 객체를 Model로 사용하는 대신에 별도의 Model 객체를 만들어서 반환하면 된다.

이렇게 하면 구현 코드도 단순해지고, 테스트 코드 작성이 쉽다.

#### 뷰 이름 중복 제거

컨트롤러는 뷰의 논리 이름만 반환하도록 한다.

```java
 public class ModelView {
 private String viewName;
 private Map<String, Object> model = new HashMap<>();
 public ModelView(String viewName) {
 this.viewName = viewName;
    }
 public String getViewName() {
 return viewName;
    }
 public void setViewName(String viewName) {
 this.viewName = viewName;
    }
 public Map<String, Object> getModel() {
 return model;
    }
 public void setModel(Map<String, Object> model) {
 this.model = model;
    }
 }
```

```java
 public interface ControllerV3 {
 ModelView process(Map<String, String> paramMap);
 }
```

이 컨트롤러는 서블릿 기술을 사용하지 않는다.

```java
 public class MemberSaveControllerV3 implements ControllerV3 {
 private MemberRepository memberRepository = MemberRepository.getInstance();
    @Override
 public ModelView process(Map<String, String> paramMap) {
 String username = paramMap.get("username");
 int age = Integer.parseInt(paramMap.get("age"));
 Member member = new Member(username, age);
        memberRepository.save(member);
 ModelView mv = new ModelView("save-result");
        mv.getModel().put("member", member);
 return mv;
    }
 }
```

파라미터 정보는 map에 담겨있다.

```java
Map<String, String> paramMap = createParamMap(request);
 ModelView mv = controller.process(paramMap);
 String viewName = mv.getViewName();
 MyView view = viewResolver(viewName);
        view.render(mv.getModel(), request, response);
    }
 private Map<String, String> createParamMap(HttpServletRequest request) {
 Map<String, String> paramMap = new HashMap<>();
        request.getParameterNames().asIterator()
                .forEachRemaining(paramName -> paramMap.put(paramName, 
request.getParameter(paramName)));
 return paramMap;
    }
private MyView viewResolver(String viewName) {
 return new MyView("/WEB-INF/views/" + viewName + ".jsp");
    }
```

request의 파라미터들을 맵에 담아서 controller로 보내고 결과를 담은 modelview를 반환받는다. 뷰이름도 viewResolver로 물리 뷰 경로로 바꾼 뒤 모델 정보를 파라미터로 뷰 객체를 통해 렌더링한다.

```java
public void render(Map<String, Object> model, HttpServletRequest request, 
HttpServletResponse response) throws ServletException, IOException {
 modelToRequestAttribute(model, request);
 RequestDispatcher dispatcher = request.getRequestDispatcher(viewPath);
        dispatcher.forward(request, response);
    }
 private void modelToRequestAttribute(Map<String, Object> model, 
HttpServletRequest request) {
        model.forEach((key, value) -> request.setAttribute(key, value));
    }
```

JSP는 request.getAttribute로 데이터를 조회하기 때문에 모델의 데이터를 꺼내서 request에 담는다.

### ViewName만 반환, 단순화 - v4

```java
public interface ControllerV4 {
    /**
     * @param paramMap
     @param model
     @return viewName
     */
String process(Map<String, String> paramMap, Map<String, Object> model);
 }
```

```java
 public class MemberSaveControllerV4 implements ControllerV4 {
 private MemberRepository memberRepository = MemberRepository.getInstance();
    @Override
 public String process(Map<String, String> paramMap, Map<String, Object> 
model) {
 String username = paramMap.get("username");
 int age = Integer.parseInt(paramMap.get("age"));
 Member member = new Member(username, age);
        memberRepository.save(member);
        model.put("member", member);
 return "save-result";
    }
 }
```

model에 넣고 뷰의 이름만 반환하면 됨

```java
 public class FrontControllerServletV4 extends HttpServlet {
 Map<String, String> paramMap = createParamMap(request);
 Map<String, Object> model = new HashMap<>(); //추가
String viewName = controller.process(paramMap, model);
 MyView view = viewResolver(viewName);
        view.render(model, request, response);
 }
```

### 유연한 컨트롤러1 - v5

#### 어댑터 패턴

지금까지 우리가 개발한 프론트 컨트롤러는 한가지 방식의 컨트롤러 인터페이스만 사용할 수 있다.

어댑터 패턴을 사용해서 프론트 컨트롤러가 다양한 방식의 컨트롤러를 처리할 수 있도록 변경해보자.

```java
 public interface MyHandlerAdapter {
 boolean supports(Object handler);
 ModelView handle(HttpServletRequest request, HttpServletResponse response, 
Object handler) throws ServletException, IOException;
 }
```

* supports메서드는 어댑터가 해당 컨트롤러를 처리할 수 있는지 판단한다.
* handle 메서드는 실제 컨트롤러를 호출하고 결과를 반환받는다.

```java
 public class ControllerV3HandlerAdapter implements MyHandlerAdapter {
    @Override
 public boolean supports(Object handler) {
 return (handler instanceof ControllerV3);
    }
    @Override
 public ModelView handle(HttpServletRequest request, HttpServletResponse 
response, Object handler) {
ControllerV3 controller = (ControllerV3) handler;
 Map<String, String> paramMap = createParamMap(request);
 ModelView mv = controller.process(paramMap);
 return mv;
    }
```

handler는 Object 타입이므로 캐스팅 필요

#### FrontControllerServletV5

```java
 public class FrontControllerServletV5 extends HttpServlet {
 private final Map<String, Object> handlerMappingMap = new HashMap<>();
 private final List<MyHandlerAdapter> handlerAdapters = new ArrayList<>();
 public FrontControllerServletV5() {
 initHandlerMappingMap();
 initHandlerAdapters();
    }
 private void initHandlerMappingMap() {
        handlerMappingMap.put("/front-controller/v5/v3/members/new-form", new 
MemberFormControllerV3());
        handlerMappingMap.put("/front-controller/v5/v3/members/save", new 
MemberSaveControllerV3());
        handlerMappingMap.put("/front-controller/v5/v3/members", new 
MemberListControllerV3());
    }
 private void initHandlerAdapters() {
        handlerAdapters.add(new ControllerV3HandlerAdapter());
    }
    @Override
 protected void service(HttpServletRequest request, HttpServletResponse 
response)
 throws ServletException, IOException {
 Object handler = getHandler(request);
 if (handler == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
 return;
        }
 MyHandlerAdapter adapter = getHandlerAdapter(handler);
 ModelView mv = adapter.handle(request, response, handler);
 MyView view = viewResolver(mv.getViewName());
        view.render(mv.getModel(), request, response);
    }
 private Object getHandler(HttpServletRequest request) {
 String requestURI = request.getRequestURI();
 return handlerMappingMap.get(requestURI);
    }
 private MyHandlerAdapter getHandlerAdapter(Object handler) {
 for (MyHandlerAdapter adapter : handlerAdapters) {
 if (adapter.supports(handler)) {
 return adapter;
            }
        }
 throw new IllegalArgumentException("handler adapter를 찾을 수 없습니다. 
handler=" + handler);
    }
 private MyView viewResolver(String viewName) {
 return new MyView("/WEB-INF/views/" + viewName + ".jsp");
    }
```

해당 핸들러를 처리할 수 있는 어댑터를 조회해서 컨트롤러를 호출하고 결과값을 반환받는다.

### 유연한 컨트롤러2 -v5

```java
 private void initHandlerMappingMap() {
 //V4 추가
    handlerMappingMap.put("/front-controller/v5/v4/members/new-form", new 
MemberFormControllerV4());
    handlerMappingMap.put("/front-controller/v5/v4/members/save", new 
MemberSaveControllerV4());
    handlerMappingMap.put("/front-controller/v5/v4/members", new 
MemberListControllerV4());
 }
```

```java
private void initHandlerAdapters() {
    handlerAdapters.add(new ControllerV3HandlerAdapter());
    handlerAdapters.add(new ControllerV4HandlerAdapter()); //V4 추가
}
```

```java
public class ControllerV4HandlerAdapter implements MyHandlerAdapter {
    @Override
 public boolean supports(Object handler) {
 return (handler instanceof ControllerV4);
    }
    @Override
 public ModelView handle(HttpServletRequest request, HttpServletResponse 
response, Object handler) {
 ControllerV4 controller = (ControllerV4) handler;
 Map<String, String> paramMap = createParamMap(request);
 Map<String, Object> model = new HashMap<>();
 String viewName = controller.process(paramMap, model);
 ModelView mv = new ModelView(viewName);
        mv.setModel(model);
 return mv;
    }
}
```

어댑터가 호출하는 ControllerV4는 뷰의 이름을 반환한다. 그런데 어댑터는 뷰의 이름이 아니라 ModelView를 반환해야 한다.

